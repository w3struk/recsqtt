use crate::web_panel::*;

#[test]
fn process_memory_reads_linux_status_fields() {
    let memory = process_memory(
        "VmHWM:\t850000 kB\nVmRSS:\t400000 kB\nRssAnon:\t350000 kB\nRssFile:\t45000 kB\nRssShmem:\t5000 kB\nVmSwap:\t12000 kB\n",
    );
    assert_eq!(memory.rss, 400000);
    assert_eq!(memory.peak, 850000);
    assert_eq!(memory.anonymous, 350000);
    assert_eq!(memory.file, 45000);
    assert_eq!(memory.shared, 5000);
    assert_eq!(memory.swap, 12000);
}

#[test]
fn process_memory_rollup_reads_attribution_fields() {
    let memory = process_memory_rollup(
        "Pss:\t700 kB\nPss_Anon:\t650 kB\nPss_File:\t40 kB\nPss_Shmem:\t10 kB\nAnonymous:\t680 kB\nShared_Clean:\t12 kB\nShared_Dirty:\t3 kB\nPrivate_Clean:\t20 kB\nPrivate_Dirty:\t640 kB\nSwapPss:\t7 kB\n",
    );
    assert_eq!(memory.pss, 700);
    assert_eq!(memory.pss_anon, 650);
    assert_eq!(memory.anonymous, 680);
    assert_eq!(memory.file, 40);
    assert_eq!(memory.shmem, 10);
    assert_eq!(memory.private, 660);
    assert_eq!(memory.shared, 15);
    assert_eq!(memory.swap_pss, 7);
}
