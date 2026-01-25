//
// Created by chen on 2026/1/24.
//

#include "wbif.h"

#include <iomanip>
#include <strings.h>

std::string wbstate2str(WBState state) {
    switch (state) {
        case WBState::idle:
            return "idle";
        case WBState::valid_cycle0:
            return "valid_cycle0";
        case WBState::valid_cycle1:
            return "valid_cycle1";
        case WBState::valid_ack:
            return "valid_ack";
        case WBState::err:
            return "err";
        default:
            return "unknown";
    }
}
wbif::wbif() {
    std::cout <<"initial magic mem" << std::endl;
    //initial state
    state = WBState::idle;
    data_buf = 0xff;
    // binding IO
    io.ack = [this]() -> bool {
        if (state == WBState::valid_ack) {
            return true;
        }else {
            return false;
        }
    };
    io.err = [this]() -> bool {
        if (state == WBState::err) {
            return true;
        }else {
            return false;
        }
    };
    io.data_r = [this]() -> uint32_t {
        //std::cout << "provide data_r : " << "0x" << std::setfill('0') << std::setw(8) << std::hex << data_buf << std::dec << std::endl;
        return data_buf;
    };
}

void wbif::tick() {
    bool advance = io.cyc && io.stb;
    std::cout << "[WBIF] Current State: " << wbstate2str(state) << ", advance: " << advance << std::endl;
    switch (state) {
        case WBState::idle:
            if (advance) {
                state = WBState::valid_cycle0;
                break;;
            }else {
                state = WBState::idle;
                break;;
            }
        case WBState::valid_cycle0:
            if (advance) {
                state = WBState::valid_cycle1;
                break;;
            }else {
                state = WBState::err;
                break;;
            }
        case WBState::valid_cycle1:
            if (advance) {
                state = WBState::valid_ack;
            }else {
                state = WBState::err;
            }
            break;;
        case WBState::valid_ack:
            if (true) {
                // process we request
                if (io.we) {
                    //write
                    uint64_t base_addr = io.adr << 2;
                    for (int sel_index =0;sel_index <=3;sel_index++) {
                        uint64_t current_addr = base_addr + sel_index;
                        bool current_mask = (io.sel >> sel_index)&0x1;
                        uint8_t current_byte = (io.data_w >> (sel_index*8))& 0xff;
                        if (current_mask) {
                            this->content.write(current_addr, current_byte);
                            std::cout << "since " << sel_index << "is 1 " << "write " << current_addr << "with " << current_byte << " byte" << std::endl;
                        }else {
                            //
                            std::cout << "since " << sel_index << "is 0" << " write NONE" << std::endl;
                        }
                    }
                }else {
                    //read
                    uint64_t base_addr = io.adr << 2;
                    uint32_t rbuf[4];
                    for (int sel_index =0;sel_index <=3;sel_index++) {
                        uint64_t current_addr = base_addr + sel_index;
                        rbuf[sel_index] = this->content.read(current_addr);
                        std::cout << "Read byte " << "0x" << std::setfill('0') << std::setw(2) << std::hex << (uint32_t)rbuf[sel_index] << " from " << "0x" << std::setfill('0') << std::setw(16) << std::hex << current_addr << std::dec << std::endl;
                    }
                    data_buf = rbuf[0]&0xff | (rbuf[1]&0xff) << 8 | (rbuf[2]&0xff)<< 16 | (rbuf[3]&0xff) << 24;
                    std :: cout << "Read " << "0x" << std::setfill('0') << std::setw(8) << std::hex << data_buf << " from " << "0x" << std::setfill('0') << std::setw(16) << std::hex << (io.adr << 2) << std::dec << std::endl;
                }
                state = WBState::idle;
                break;;
            }else {
                state = WBState::err;
                break;;
            }
        case WBState::err:
            state = WBState::idle;
            break;;
        default:
            std::cout << "unknown state" << std::endl;
    }
    std::cout << "[WBIF] Next State: " << wbstate2str(state) << std::endl;
}

void wbif::initial_mem(uint64_t start_addr, char *data, int len) {
    this -> content.loadmem(start_addr, data, len);
}

void wbif::initial_mem_hex(uint64_t start_addr, uint64_t *data, int len) {
    for (int index=0;index < len;index ++ ) {
        uint64_t line = data[index];
        for (int byte_id=0;byte_id < 8;byte_id++) {
            uint64_t dst_addr = start_addr + index*8 + byte_id;
            uint8_t data = (line >> byte_id*8) & 0xff;
            this->content.write(dst_addr, data);
            std::cout << "write byte " << "0x" << std::setfill('0') << std::setw(2) << std::hex << (uint32_t)data << " to " << "0x" << dst_addr << std::endl;
        }
        std::cout << "write " << "0x" << std::setfill('0') << std::setw(16) << std::hex << line << " to " << "0x" << start_addr + index*8 << std::endl;
    }
    std::cout << std::dec<< "finish initial" << std::endl;
}
