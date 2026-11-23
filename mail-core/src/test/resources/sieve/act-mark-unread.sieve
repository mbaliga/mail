if anyof(header :contains "from" "alice", address :contains "sender" "alice") {
    removeflag "\\Seen";
}
