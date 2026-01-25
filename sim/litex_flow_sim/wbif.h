//
// Created by chen on 2026/1/24.
//

#ifndef LITEX_FLOW_SIM_WBIF_H
#define LITEX_FLOW_SIM_WBIF_H
#include <functional>
#include <string>
#include "wbmem.h"
/**
 * @brief 作为中间层连接cpu与模拟内存
 */
struct wishbone {
    uint64_t adr;
    uint32_t data_w;
    std::function<uint32_t()> data_r;
    uint8_t sel;
    bool cyc;
    bool stb;
    std::function<bool()> ack;
    bool we;
    uint8_t bte;
    uint8_t cti;
    std::function<bool()>err;
};

enum class WBState:uint8_t {
    idle,// 应有的在两个stb之间的空闲
    valid_cycle0,
    valid_cycle1,
    valid_ack,
    err
};

std::string wbstate2str(WBState state);

class wbif {
public:
    struct wishbone io;
    wbif();
    wbif(int delay);
    void tick();
    void step();
    void initial_mem(uint64_t start_addr,char*data,int len);
    void initial_mem_hex(uint64_t start_addr, uint64_t*data, int len);
    uint64_t get_count();
private:
    wbmem content;
    /**
     * 使用状态来记录当前的读写进行到那一步，每次tick,只是根据当前的输入更新状态
     * 现在，先暂时固定每次需要两个周期才能返回ack
     */
    WBState state;
    uint32_t data_buf;
    uint8_t count;
    uint8_t delay;
};

#endif //LITEX_FLOW_SIM_WBIF_H