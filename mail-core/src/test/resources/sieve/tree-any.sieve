if anyof(header :contains "list-id" "a", header :contains "list-id" "b") {
    addflag "\\Flagged";
}
