#include "flow_sim.hpp"
#include <iostream>
#include <map>
int main(void){
    std::cout << "Start flow_sim test" << std::endl;
    std::cout << "Create some CSR instances" << std::endl;
    auto& printer = CSR<0xf0>::getInstance();
    std::cout << "CSR address: 0x" << std::hex << printer.addr() << std::endl;
    printer.set(0x12345678);
    std::cout << "CSR value: 0x" << std::hex << printer.get() << std::endl;
    auto& another_printer = CSR<0xf0>::getInstance();
    std::cout << "Another CSR address: 0x" << std::hex << another_printer.addr() << std::endl;
    std::cout << "Another CSR value: 0x" << std::hex << another_printer.get() << std::endl;
    std::map<uint16_t, CSRBase*> csr_map_test;
    add_csr_instance(csr_map_test, printer);
    add_csr_instance(csr_map_test, another_printer);
    std::cout << "CSR map size: " << csr_map_test.size() << std::endl;
    csr_map_test[0xf0]->set(0xdeadbeef);
    std::cout << "CSR map CSR value: 0x" << std::hex << csr_map_test[0xf0]->get() << std::endl;
    std::cout << "Printer CSR value after map set: 0x" << std::hex << printer.get() << std::endl;
    add_csr_instance<0x1>(csr_map_test);
    csr_map_test[0x1]->set(0x1122334455667788);
    std::cout << "CSR map CSR 0x1 value: 0x" << std::hex << csr_map_test[0x1]->get() << std::endl;
    return 0;
}