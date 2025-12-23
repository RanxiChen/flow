NR % 2 == 1 {
    second = $0
    next
}
NR % 2 == 0 {
    OFS=""
    print $0, second
}
END {
    if (NR % 2 == 1)
        print "00000013", second
}