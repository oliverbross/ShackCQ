#include <IOKit/IOKitLib.h>
#include <mach/mach.h>
#include <stdint.h>
#include <stddef.h>

namespace {
constexpr const char* kServiceName = "ShackCQPL2303";
constexpr uint32_t kStatusSelector = 0;
constexpr uint32_t kWriteSelector = 1;
constexpr uint32_t kReadSelector = 2;

io_service_t firstService()
{
	io_iterator_t iterator = IO_OBJECT_NULL;
	CFMutableDictionaryRef matching = IOServiceNameMatching(kServiceName);
	if (matching == nullptr ||
		IOServiceGetMatchingServices(kIOMainPortDefault, matching, &iterator) != KERN_SUCCESS)
		return IO_OBJECT_NULL;
	io_service_t service = IOIteratorNext(iterator);
	IOObjectRelease(iterator);
	return service;
}
}

extern "C" int32_t shackcq_kxusb_available(void)
{
	io_service_t service = firstService();
	if (service == IO_OBJECT_NULL) return 0;
	IOObjectRelease(service);
	return 1;
}

extern "C" int32_t shackcq_kxusb_open(uint32_t* connection)
{
	if (connection == nullptr) return kIOReturnBadArgument;
	*connection = 0;
	io_service_t service = firstService();
	if (service == IO_OBJECT_NULL) return kIOReturnNotFound;
	io_connect_t newConnection = IO_OBJECT_NULL;
	// Match Apple's iPadOS DriverKit user-client sample connection type.
	kern_return_t ret = IOServiceOpen(service, mach_task_self(), 1, &newConnection);
	IOObjectRelease(service);
	if (ret != KERN_SUCCESS) return ret;

	uint64_t ready = 0;
	uint32_t readyCount = 1;
	ret = IOConnectCallScalarMethod(newConnection, kStatusSelector, nullptr, 0, &ready, &readyCount);
	if (ret != KERN_SUCCESS || ready != 1) {
		IOServiceClose(newConnection);
		return ret == KERN_SUCCESS ? kIOReturnNotReady : ret;
	}
	*connection = newConnection;
	return KERN_SUCCESS;
}

extern "C" void shackcq_kxusb_close(uint32_t connection)
{
	if (connection != 0) IOServiceClose(connection);
}

extern "C" int32_t shackcq_kxusb_write(uint32_t connection, const uint8_t* bytes, size_t length)
{
	if (connection == 0 || bytes == nullptr || length == 0 || length > 128)
		return kIOReturnBadArgument;
	return IOConnectCallStructMethod(connection, kWriteSelector, bytes, length, nullptr, nullptr);
}

extern "C" int32_t shackcq_kxusb_read(uint32_t connection, uint8_t* bytes, size_t capacity,
	size_t* transferred)
{
	if (connection == 0 || bytes == nullptr || transferred == nullptr || capacity == 0)
		return kIOReturnBadArgument;
	*transferred = capacity;
	return IOConnectCallStructMethod(connection, kReadSelector, nullptr, 0, bytes, transferred);
}
