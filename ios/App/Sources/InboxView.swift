import SwiftUI
import MailKit

struct InboxView: View {
    @EnvironmentObject var session: MailSession

    var body: some View {
        List {
            Section {
                ForEach(session.rows) { row in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            // Shape + weight, not colour alone, marks unread.
                            if !row.seen { Image(systemName: "circle.fill").font(.system(size: 7)).accessibilityLabel("Unread") }
                            Text(row.from).fontWeight(row.seen ? .regular : .semibold)
                            Spacer()
                            if let d = row.date { Text(d, style: .date).font(.caption).foregroundStyle(.secondary) }
                        }
                        Text(row.subject.isEmpty ? "(no subject)" : row.subject).font(.subheadline).lineLimit(2)
                        if let code = row.otp {
                            Text("Code: \(code)").font(.caption.monospaced()).foregroundStyle(.secondary)
                        }
                    }
                }
            } header: {
                // The headline is always true about the list beneath it.
                Text("You've got \(session.unseenCount) new mail \u{00B7} on \(session.account?.providerName ?? "")")
                    .font(.headline).textCase(nil)
            } footer: {
                if let e = session.errorText { Text(e) }
            }
        }
        .navigationTitle("Inbox")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("Refresh") { Task { await session.refresh() } }
                    Button("Sign out", role: .destructive) { session.signOut() }
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
        .overlay { if session.loading && session.rows.isEmpty { ProgressView() } }
        .refreshable { await session.refresh() }
        .task { if session.rows.isEmpty { await session.refresh() } }
    }
}
