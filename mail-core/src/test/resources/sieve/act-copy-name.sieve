if anyof(header :contains "from" "alice", address :contains "sender" "alice") {
    fileinto :copy "Backup";
}
