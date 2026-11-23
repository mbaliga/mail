if anyof(anyof(header :contains "from" "alice", address :contains "sender" "alice"), address :domain :matches "from" ["acme.io", "*.acme.io"]) {
    addflag "\\Flagged";
}
