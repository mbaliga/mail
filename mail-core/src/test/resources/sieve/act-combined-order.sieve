if anyof(header :contains "from" "alice", address :contains "sender" "alice") {
    addflag "\\Seen";
    addflag "\\Flagged";
    redirect :copy "x@y.z";
    fileinto :copy "Backup";
    fileinto "Archive";
}
