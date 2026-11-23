if allof(anyof(anyof(header :contains "from" "alice", address :contains "sender" "alice"), anyof(header :contains "from" "bob", address :contains "sender" "bob")), not header :contains "subject" "spam", size :over 1048576) {
    addflag "\\Flagged";
}
