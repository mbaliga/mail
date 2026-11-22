if anyof(header :contains "from" "alice", address :contains "sender" "alice") {
    redirect :copy "boss@corp.com";
}
