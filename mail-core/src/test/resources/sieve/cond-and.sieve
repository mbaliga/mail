if allof(anyof(header :contains "from" "alice", address :contains "sender" "alice"), header :contains "subject" "lunch") {
    addflag "\\Flagged";
}
