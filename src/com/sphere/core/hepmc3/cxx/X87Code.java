package com.sphere.core.hepmc3.cxx;

/**
 * The machine code of the Sphere x87 ABI, bridge/lib/sphere_x87.S assembled for the two x86-64
 * calling conventions by hepmc3/cxx/x87/build.sh (gcc.exe (Rev5, Built by MSYS2 project) 16.1.0): do not edit, run
 * x87/build.sh. The code is position independent and without relocations; X87Abi copies it
 * into executable memory.
 */
final class X87Code {

    private X87Code() {
    }

    /** The first 16 hex digits of the SHA-256 of the source assembled. */
    static final String SOURCE_SHA256 = "de7d2988741ac02d";

    /** Windows (Win64 convention). */
    static final String WIN64 =
        "d97c240866c744240a7f03d96c240adb2adb29d8c141db38ddd8d96c2408c390d97c240866c744240a7f03d96c240adb"
        + "2adb29d8e141db38ddd8d96c2408c390d97c240866c744240a7f03d96c240adb2adb29d8c941db38ddd8d96c2408c390"
        + "d97c240866c744240a7f03d96c240adb2adb29d8f141db38ddd8d96c2408c390d97c240866c744240a7f03d96c240adb"
        + "29d9fadb3ad96c2408c3660f1f440000d97c240866c744240a7f03d96c240adb2adb29dfe9ddd87a1f720c7414b80100"
        + "0000d96c2408c3b8ffffffffd96c2408c331c0d96c2408c3b802000000d96c2408c30f1f0066662e0f1f840000000000"
        + "d97c240866c744240a7f03d96c240add01db3ad96c2408c30f1f840000000000d97c240866c744240a7f03d96c240adb"
        + "29dd1ad96c2408c30f1f840000000000d97c240866c744240a7f03d96c240adf29db3ad96c2408c30f1f840000000000"
        + "d97c240866c744240a7f0fd96c240adb29df3ad96c2408c30f1f840000000000d97c240866c744240a7f03d96c240adb"
        + "29d9fedfe0a9000400007507db3ad96c2408c3d9ebd8c0d9c9d9f5dfe0a90004000075f5ddd9d9fedb3ad96c2408c390"
        + "d97c240866c744240a7f03d96c240adb29d9ffdfe0a9000400007507db3ad96c2408c3d9ebd8c0d9c9d9f5dfe0a90004"
        + "000075f5ddd9d9ffdb3ad96c2408c390d97c240866c744240a7f03d96c240adb29d9c0d8c8d9e8dee1d9fad9f3db3ad9"
        + "6c2408c39066662e0f1f840000000000d97c240866c744240a7f03d96c240adb29db2ad9f341db38d96c2408c3909090";

    static final String[] WIN64_SYMBOLS = {"sx87_add:0", "sx87_sub:32", "sx87_mul:64", "sx87_div:96", "sx87_sqrt:128", "sx87_cmp:160", "sx87_from_f64:240", "sx87_to_f64:272", "sx87_from_i64:304", "sx87_to_i64:336", "sx87_sin:368", "sx87_cos:432", "sx87_asin:496", "sx87_atan2:544"};

    /** Linux, macOS Intel, BSD (System V convention). */
    static final String SYSV =
        "d97c24f866c74424fa7f03d96c24fadb2edb2fd8c1db3addd8d96c24f8c36690d97c24f866c74424fa7f03d96c24fadb"
        + "2edb2fd8e1db3addd8d96c24f8c36690d97c24f866c74424fa7f03d96c24fadb2edb2fd8c9db3addd8d96c24f8c36690"
        + "d97c24f866c74424fa7f03d96c24fadb2edb2fd8f1db3addd8d96c24f8c36690d97c24f866c74424fa7f03d96c24fadb"
        + "2fd9fadb3ed96c24f8c3660f1f440000d97c24f866c74424fa7f03d96c24fadb2edb2fdfe9ddd87a1f720c7414b80100"
        + "0000d96c24f8c3b8ffffffffd96c24f8c331c0d96c24f8c3b802000000d96c24f8c30f1f0066662e0f1f840000000000"
        + "d97c24f866c74424fa7f03d96c24fadd07db3ed96c24f8c30f1f840000000000d97c24f866c74424fa7f03d96c24fadb"
        + "2fdd1ed96c24f8c30f1f840000000000d97c24f866c74424fa7f03d96c24fadf2fdb3ed96c24f8c30f1f840000000000"
        + "d97c24f866c74424fa7f0fd96c24fadb2fdf3ed96c24f8c30f1f840000000000d97c24f866c74424fa7f03d96c24fadb"
        + "2fd9fedfe0a9000400007507db3ed96c24f8c3d9ebd8c0d9c9d9f5dfe0a90004000075f5ddd9d9fedb3ed96c24f8c390"
        + "d97c24f866c74424fa7f03d96c24fadb2fd9ffdfe0a9000400007507db3ed96c24f8c3d9ebd8c0d9c9d9f5dfe0a90004"
        + "000075f5ddd9d9ffdb3ed96c24f8c390d97c24f866c74424fa7f03d96c24fadb2fd9c0d8c8d9e8dee1d9fad9f3db3ed9"
        + "6c24f8c39066662e0f1f840000000000d97c24f866c74424fa7f03d96c24fadb2fdb2ed9f3db3ad96c24f8c390909090";

    static final String[] SYSV_SYMBOLS = {"sx87_add:0", "sx87_sub:32", "sx87_mul:64", "sx87_div:96", "sx87_sqrt:128", "sx87_cmp:160", "sx87_from_f64:240", "sx87_to_f64:272", "sx87_from_i64:304", "sx87_to_i64:336", "sx87_sin:368", "sx87_cos:432", "sx87_asin:496", "sx87_atan2:544"};
}
