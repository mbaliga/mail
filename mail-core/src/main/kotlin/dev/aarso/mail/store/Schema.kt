package dev.aarso.mail.store

/**
 * The on-device schema. Versioned with `PRAGMA user_version`; each migration is additive and runs
 * inside the opening transaction-free path exactly once.
 *
 * Shape notes:
 *  - `message.labels` holds Gmail labels (and JMAP extra mailboxes) unit-separator delimited,
 *    `\u001F\\Inbox\u001FReceipts\u001F`, so membership is one LIKE with no JSON functions.
 *  - `message_fts` is an FTS5 table keyed by message rowid. It is written by code, not triggers: the
 *    body column holds the preview until the full text arrives, then the new content only — quoted
 *    history would make every reply in a thread match every term of the original.
 *  - Derived facts (category, tl;dr, OTP, priority) live on the message row so the digest is one
 *    indexed query, and each carries its provenance.
 */
object Schema {
    const val VERSION = 4

    private val V1 = """
        CREATE TABLE IF NOT EXISTS account (
            id INTEGER PRIMARY KEY,
            json TEXT NOT NULL,
            sort_order INTEGER NOT NULL DEFAULT 0
        );

        CREATE TABLE IF NOT EXISTS mailbox (
            id INTEGER PRIMARY KEY,
            account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
            remote_id TEXT NOT NULL,
            name TEXT NOT NULL,
            delimiter TEXT,
            role TEXT NOT NULL DEFAULT 'NONE',
            selectable INTEGER NOT NULL DEFAULT 1,
            subscribed INTEGER NOT NULL DEFAULT 1,
            sync_enabled INTEGER NOT NULL DEFAULT 1,
            uid_validity INTEGER NOT NULL DEFAULT 0,
            uid_next INTEGER NOT NULL DEFAULT 0,
            highest_modseq INTEGER NOT NULL DEFAULT 0,
            sync_state TEXT,
            total INTEGER NOT NULL DEFAULT 0,
            unread INTEGER NOT NULL DEFAULT 0,
            last_sync_at INTEGER NOT NULL DEFAULT 0,
            sort_order INTEGER NOT NULL DEFAULT 0,
            UNIQUE(account_id, remote_id)
        );

        CREATE TABLE IF NOT EXISTS message (
            id INTEGER PRIMARY KEY,
            account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
            mailbox_id INTEGER NOT NULL REFERENCES mailbox(id) ON DELETE CASCADE,
            uid INTEGER NOT NULL DEFAULT 0,
            remote_id TEXT,
            provider_msg_id TEXT,
            message_id TEXT,
            in_reply_to TEXT,
            refs TEXT,
            thread_key TEXT NOT NULL DEFAULT '',
            subject TEXT NOT NULL DEFAULT '',
            from_addr TEXT,
            from_name TEXT,
            sender_addr TEXT,
            reply_to TEXT,
            to_list TEXT,
            cc_list TEXT,
            bcc_list TEXT,
            sent_at INTEGER NOT NULL DEFAULT 0,
            received_at INTEGER NOT NULL DEFAULT 0,
            size INTEGER NOT NULL DEFAULT 0,
            flags INTEGER NOT NULL DEFAULT 0,
            labels TEXT NOT NULL DEFAULT '',
            preview TEXT NOT NULL DEFAULT '',
            attachment_count INTEGER NOT NULL DEFAULT 0,
            has_calendar INTEGER NOT NULL DEFAULT 0,
            list_id TEXT,
            list_unsubscribe TEXT,
            list_unsubscribe_post INTEGER NOT NULL DEFAULT 0,
            is_bulk INTEGER NOT NULL DEFAULT 0,
            is_automated INTEGER NOT NULL DEFAULT 0,
            parts TEXT,
            body_state INTEGER NOT NULL DEFAULT 0,
            category TEXT NOT NULL DEFAULT 'other',
            category_conf REAL NOT NULL DEFAULT 0,
            category_src INTEGER NOT NULL DEFAULT 0,
            tldr TEXT,
            tldr_src INTEGER NOT NULL DEFAULT 0,
            otp_code TEXT,
            language TEXT,
            priority REAL NOT NULL DEFAULT 0,
            snoozed_until INTEGER NOT NULL DEFAULT 0,
            pinned INTEGER NOT NULL DEFAULT 0,
            muted INTEGER NOT NULL DEFAULT 0,
            tracker_count INTEGER NOT NULL DEFAULT 0,
            auth_results TEXT,
            hints TEXT,
            hidden INTEGER NOT NULL DEFAULT 0
        );
        CREATE UNIQUE INDEX IF NOT EXISTS message_mailbox_uid ON message(mailbox_id, uid) WHERE uid > 0;
        CREATE UNIQUE INDEX IF NOT EXISTS message_remote ON message(account_id, remote_id) WHERE remote_id IS NOT NULL;
        CREATE INDEX IF NOT EXISTS message_mailbox_date ON message(mailbox_id, received_at DESC);
        CREATE INDEX IF NOT EXISTS message_account_date ON message(account_id, received_at DESC);
        CREATE INDEX IF NOT EXISTS message_date ON message(received_at DESC);
        CREATE INDEX IF NOT EXISTS message_thread ON message(thread_key);
        CREATE INDEX IF NOT EXISTS message_msgid ON message(message_id);
        CREATE INDEX IF NOT EXISTS message_category ON message(category, received_at DESC);
        CREATE INDEX IF NOT EXISTS message_from ON message(from_addr);
        CREATE INDEX IF NOT EXISTS message_provider ON message(account_id, provider_msg_id);
        CREATE INDEX IF NOT EXISTS message_snoozed ON message(snoozed_until) WHERE snoozed_until > 0;

        CREATE TABLE IF NOT EXISTS body (
            message_id INTEGER PRIMARY KEY REFERENCES message(id) ON DELETE CASCADE,
            text TEXT,
            html TEXT,
            attachments TEXT,
            structured TEXT,
            fetched_at INTEGER NOT NULL DEFAULT 0
        );

        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
            subject, sender, recipients, body, attachments, labels,
            tokenize = 'unicode61 remove_diacritics 2'
        );

        CREATE TABLE IF NOT EXISTS pending_op (
            id INTEGER PRIMARY KEY,
            account_id INTEGER NOT NULL,
            kind TEXT NOT NULL,
            payload TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            not_before INTEGER NOT NULL DEFAULT 0,
            attempts INTEGER NOT NULL DEFAULT 0,
            last_error TEXT
        );
        CREATE INDEX IF NOT EXISTS pending_op_account ON pending_op(account_id, id);

        CREATE TABLE IF NOT EXISTS outbox (
            id INTEGER PRIMARY KEY,
            account_id INTEGER NOT NULL,
            draft TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'QUEUED',
            send_at INTEGER NOT NULL DEFAULT 0,
            attempts INTEGER NOT NULL DEFAULT 0,
            last_error TEXT,
            created_at INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS draft (
            id INTEGER PRIMARY KEY,
            account_id INTEGER NOT NULL,
            json TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS rule (
            id INTEGER PRIMARY KEY,
            json TEXT NOT NULL,
            sort_order INTEGER NOT NULL DEFAULT 0
        );

        CREATE TABLE IF NOT EXISTS saved_search (
            id INTEGER PRIMARY KEY,
            json TEXT NOT NULL,
            sort_order INTEGER NOT NULL DEFAULT 0
        );

        CREATE TABLE IF NOT EXISTS contact (
            address TEXT PRIMARY KEY,
            name TEXT,
            sent_count INTEGER NOT NULL DEFAULT 0,
            received_count INTEGER NOT NULL DEFAULT 0,
            last_contact_at INTEGER NOT NULL DEFAULT 0
        );

        CREATE TABLE IF NOT EXISTS setting (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS reminder (
            id INTEGER PRIMARY KEY,
            message_id INTEGER NOT NULL,
            kind TEXT NOT NULL,
            due_at INTEGER NOT NULL,
            done INTEGER NOT NULL DEFAULT 0,
            note TEXT
        );
        CREATE INDEX IF NOT EXISTS reminder_due ON reminder(done, due_at);

        CREATE TABLE IF NOT EXISTS ai_cache (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL,
            source INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS embedding (
            message_id INTEGER PRIMARY KEY REFERENCES message(id) ON DELETE CASCADE,
            model TEXT NOT NULL,
            vector BLOB NOT NULL
        );
    """.trimIndent()

    /**
     * V2: sync looks up "the unsent copy I already have of this message" (uid 0, no remote id) by
     * Message-ID for every arriving message. With no index for it, SQLite walked the whole mailbox
     * for each one — quadratic in mailbox size. This partial index holds only those few rows.
     * The `uid > 0` lookups elsewhere now say so, which is what lets them use `message_mailbox_uid`.
     */
    private val V2 = """
        CREATE INDEX IF NOT EXISTS message_placeholder ON message(mailbox_id, message_id) WHERE uid = 0 AND remote_id IS NULL;
        -- A conversation is one equality on thread_key plus the account, already in date order. With the
        -- old single-column index SQLite preferred the account/date index (it avoids a sort) and walked
        -- the whole account to find three messages.
        DROP INDEX IF EXISTS message_thread;
        CREATE INDEX IF NOT EXISTS message_thread ON message(thread_key, account_id, received_at);
        -- Every list is `ORDER BY received_at DESC, id DESC`. With the id in the index key the scan arrives
        -- already in that order, so a list stops after its first page instead of sorting a folder.
        DROP INDEX IF EXISTS message_mailbox_date;
        DROP INDEX IF EXISTS message_account_date;
        DROP INDEX IF EXISTS message_date;
        CREATE INDEX IF NOT EXISTS message_mailbox_date ON message(mailbox_id, received_at DESC, id DESC);
        CREATE INDEX IF NOT EXISTS message_account_date ON message(account_id, received_at DESC, id DESC);
        CREATE INDEX IF NOT EXISTS message_date ON message(received_at DESC, id DESC);
        -- Counts ("298 new", "select all 4,102") and facets read a handful of columns of many rows. This index holds
        -- exactly those, so they are answered without touching the table: 149 ms -> 11 ms for unread-and-old at 100k.
        CREATE INDEX IF NOT EXISTS message_cover ON message(received_at DESC, mailbox_id, flags, hidden, snoozed_until, attachment_count, category);
    """

    /** V3: every distinct indexed word with how many messages hold it — what "did you mean" corrects against. */
    private val V3 = """
        CREATE VIRTUAL TABLE IF NOT EXISTS message_vocab USING fts5vocab(message_fts, 'row');
    """

    fun migrate(c: Conn) {
        val current = c.scalarLong("PRAGMA user_version")?.toInt() ?: 0
        if (current < 1) {
            c.execScript(V1)
        }
        if (current < 2) {
            c.execScript(V2)
        }
        if (current < 3) {
            c.execScript(V3)
        }
        if (current < 4) {
            // V4: rules v2 — rewrite each saved rule into the new shape (see RuleMigration); nothing is dropped.
            val rows = c.query("SELECT id, json, sort_order FROM rule") { r -> Triple(r.long(0), r.text(1), r.long(2).toInt()) }
            for ((id, j, order) in rows) c.exec("UPDATE rule SET json = ? WHERE id = ?", dev.aarso.mail.rules.RuleMigration.upgrade(j, order), id)
        }
        if (current != VERSION) c.execScript("PRAGMA user_version = $VERSION")
    }

    /** Every table name, for "everything changed" notifications after a bulk import. */
    val ALL_TABLES = setOf(
        "account", "mailbox", "message", "body", "message_fts", "pending_op", "outbox", "draft", "rule", "saved_search",
        "contact", "setting", "reminder", "ai_cache", "embedding",
    )
}
