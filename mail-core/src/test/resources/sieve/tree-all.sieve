if allof(anyof(header :contains "from" "alice", address :contains "sender" "alice"), size :over 1000) {
    addflag "\\Flagged";
}
