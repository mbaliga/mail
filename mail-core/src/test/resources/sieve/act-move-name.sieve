if anyof(header :contains "from" "alice", address :contains "sender" "alice") {
    fileinto "Receipts/2026";
}
