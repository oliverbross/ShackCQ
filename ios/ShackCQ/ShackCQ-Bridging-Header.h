#include "shackcq/core.h"

#include <stddef.h>
#include <stdint.h>

int32_t shackcq_kxusb_available(void);
int32_t shackcq_kxusb_open(uint32_t* connection);
void shackcq_kxusb_close(uint32_t connection);
int32_t shackcq_kxusb_write(uint32_t connection, const uint8_t* bytes, size_t length);
int32_t shackcq_kxusb_read(uint32_t connection, uint8_t* bytes, size_t capacity, size_t* transferred);
