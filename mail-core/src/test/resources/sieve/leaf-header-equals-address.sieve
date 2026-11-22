if anyof(header :is "from" "billing@acme.io", address :is "from" "billing@acme.io") {
    addflag "\\Flagged";
}
