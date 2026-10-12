# CoreMark 与现有 GAPBS 的 SD Linux 镜像

2026-10-12板上结果更新：用户已进入Linux并运行 `./coremark`，回传300次迭代、12.232秒、24.525834 CoreMark及 `Correct operation validated`。按普通单核调试配置100MHz换算0.24525834 CoreMark/MHz。原始日志、产品关联及分析见 [单核CoreMark板上结果](BE2-single-coremark-board-result-20261012.md)。此项是用户回传证据；后文“尚未运行”保留为构建交付当时状态。Linux SD初始化仍报-110/-22，GAPBS结果及第二组CoreMark种子尚未回传。

用户在单核ILA编译期间追加：Linux镜像同时包含CoreMark与现有GAPBS，并已把SD卡接入WSL。

Linux6.18.7的新Image已经编译完成，软件脚本源 `3a2e953b2dd1d2bc3dce39c6451ebeb074536f2a`，实际主机Alan；输出 `/home/chen/FUN/flow-runs/be2-coremark-gapbs-linux-static-20261012/images/Image`，SHA256 `524dfe764cef92e90dfe2634dbbd87e2e2c3ee71ca8052e92415cba7adb74cb7`。新rootfs SHA256 `e382ab7d23ce2fb21fe5f61fcc8416b0d9128e4f3f64eb333927edd1b3a2df6b`；kernel/usr/initramfs_inc_data解压后与新rootfs.cpio逐字节相等，证明实际内嵌镜像包含所打包文件。

CoreMark来源EEMBC1.01归档（99c5a6d63af85a281b4e4d6ccb522c446653c435dfec9455ad73ef9e71f28bde），stock linux64 port，不改算法/seed/验证值。GCC13.3.0 Linux交叉工具链，`-O3 -march=rv64imafdc -mabi=lp64d -static -lrt`；实际ELF为RISC-V64/RVC/double-float，无动态段，没有V指令集属性。安装 `/usr/bin/coremark`，附源许可证和编译信息。默认运行 `coremark`，尚未运行目标程序，不给score/CRC通过结论。

现有GAPBS由原Buildroot musl SDK/rootfs原样保留：原cpio SHA74ebde8cf79ce5caf0e418efdfc3a949d4272d73c769ac87e9d3be3f399b9282；1386个原archive条目的数据全部不变，9个GAPBS ELF逐项hash一致，`gapbs-smoke`保留Python和6个算法原 `-g 10 -n 1 -v` 自检，仍不在开机自动运行。新增3个文件仅CoreMark ELF、许可证、编译信息，目录项另建。新cpio使用Linux支持的串接newc档案；保留原硬链接分组终止边界和原设备节点元数据。

首次尝试d16a3ec使用原SDK的musl GCC14.4.0链接CoreMark，因SDK中Scrt1.o/crti.o/crtbeginS.o/libgcc.a等缺失失败，exit2/raw log保留。SDK的已编GAPBS和动态运行库未修改；改为已预检的系统GNU/Linux工具链静态编译独立CoreMark。故CoreMark与GAPBS的compiler/libc不同，性能不能假称相同工具链条件。内核仍由原SDK GCC14.4编译、仅改INITRAMFS_SOURCE；复制旧kernel对象到新输出，不用硬链接，原kernel/Image/config/rootfs/OpenSBI输入hash全部再次通过。最终compiler/包合并/kernel build exit0。

每次编译和trampoline打包前重读共享主机配置，cloud_chen SSH5秒timeout255，回退Alan并验证工具/磁盘/内存；CPU6–7/nice10/JOBS2。新完整Buildroot三profile及旧GAPBS profile也加入CoreMark包（默认musl），后续可独立从源编译；当前实际静态GNU版本由rebuild-coremark-gapbs.sh和显式COREMARK_*参数复现。

匹配单核ILA硬件的五文件启动包在Alan `/home/chen/FUN/flow-runs/be2-single-debug-coremark-gapbs-sd-20261012/`，CSR/BIOS/最终bit的准确hash绑定；单核DTB、Image/OpenSBI/trampoline/boot.json地址保持原接口。准备时重新编译DTB/trampoline，exit0。SD只存软件，不放bitstream。

SD盘只读识别：Windows Disk1 `Generic- SD/MMC/MS PRO`，USB、63864569856字节、MBR；Partition1 offset1048576/size268435456，对应E: BREEZEBOOT FAT32，Partition2另存。WSL无block直挂、sudo需密码，因此使用Windows文件接口访问E:。安装脚本逐项核对上述身份，校验源manifest，备份现有启动文件，WriteThrough写暂存、校验后按boot.json最后的顺序更新五文件，最终读取SHA256；不格式化或改分区。实际写入结果稍后按安装日志补充。

SD安装完成：五文件本地manifest及实际bit绑定先核对；Windows E:身份再次匹配后，原6个启动文件逐项SHA备份到 `E:\BE2-backup-20261011-231240`，不改分区。WriteThrough暂存校验后更新5个启动文件，最后写boot.json；最终从SD读取全部5文件，sha256_errors0，安装脚本exit0。审计日志 `records/be2-coremark-gapbs-20261012/sd-install-verification.json` 包含原文件与新文件SHA256/CRC32、设备/分区/UTC。新版Image44,165,632字节，长度虽与前版相同，但SHA不同且实际内嵌rootfs精确核对通过，不能仅按文件大小判断版本。

交付路径：Alan调试 `.bit/.ltx` 在 `/home/chen/FUN/flow-runs/be2-single-debug-20261012/gateware/`；CoreMark+GAPBS FAT包在 `/home/chen/FUN/flow-runs/be2-single-debug-coremark-gapbs-sd-20261012/`，本地在 `build/be2-products/single-debug-coremark-gapbs-sd-20261012/`。SD更新至普通单核DTB，并匹配单核调试bit；上板后运行 `coremark`、`gapbs-smoke`。新Linux/新bit尚无板上BOOT_OK或性能结果；时序/编译/哈希与板级结果继续分开。
