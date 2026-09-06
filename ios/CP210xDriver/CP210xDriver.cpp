#include <os/log.h>
#include <cstring>

#include <DriverKit/IOBufferMemoryDescriptor.h>
#include <DriverKit/IOLib.h>
#include <DriverKit/OSData.h>
#include <DriverKit/IOUserClient.h>
#include <USBDriverKit/IOUSBHostInterface.h>
#include <USBDriverKit/IOUSBHostPipe.h>

#define CP210xDriver_DECLARE_IVARS \
	IOUSBHostInterface* usbInterface; \
	IOUSBHostPipe* bulkIn; \
	IOUSBHostPipe* bulkOut; \
	bool interfaceOpen;
#include "CP210xDriver.h"

namespace {
constexpr uint8_t kBulkInEndpoint = 0x83;
constexpr uint8_t kBulkOutEndpoint = 0x02;
constexpr uint8_t kClassInterfaceOut = 0x21;
constexpr uint8_t kVendorDeviceOut = 0x40;
constexpr uint8_t kSetLineCoding = 0x20;
constexpr uint8_t kSetControlLineState = 0x22;
constexpr uint8_t kHxnWriteRegister = 0x80;
constexpr uint16_t kHxnPipeResetRegister = 0x0007;
constexpr uint16_t kHxnPipeResetBoth = 0x0003;
constexpr uint16_t kInterfaceNumber = 0;
constexpr uint32_t kControlTimeoutMs = 1000;
constexpr uint32_t kBulkTimeoutMs = 40;
constexpr uint32_t kMaximumCATPacket = 512;

}

kern_return_t
IMPL(CP210xDriver, Start)
{
	usbInterface = nullptr;
	bulkIn = nullptr;
	bulkOut = nullptr;
	interfaceOpen = false;

	kern_return_t ret = Start(provider, SUPERDISPATCH);
	if (ret != kIOReturnSuccess) return ret;

	usbInterface = OSDynamicCast(IOUSBHostInterface, provider);
	if (usbInterface == nullptr) {
		(void)Stop(provider, SUPERDISPATCH);
		return kIOReturnBadArgument;
	}
	ret = usbInterface->Open(this, 0, nullptr);
	if (ret == kIOReturnSuccess) interfaceOpen = true;
	if (ret == kIOReturnSuccess) ret = usbInterface->CopyPipe(kBulkInEndpoint, &bulkIn);
	if (ret == kIOReturnSuccess) ret = usbInterface->CopyPipe(kBulkOutEndpoint, &bulkOut);
	if (ret == kIOReturnSuccess) ret = configurePL2303();
	if (ret == kIOReturnSuccess) {
		(void)SetName("ShackCQPL2303");
		ret = RegisterService();
	}
	if (ret != kIOReturnSuccess) {
		os_log(OS_LOG_DEFAULT, "ShackCQ PL2303GC start failed: 0x%08x", ret);
		if (interfaceOpen) (void)usbInterface->Close(this, 0);
		interfaceOpen = false;
		OSSafeReleaseNULL(bulkIn);
		OSSafeReleaseNULL(bulkOut);
		usbInterface = nullptr;
		(void)Stop(provider, SUPERDISPATCH);
		return ret;
	}
	os_log(OS_LOG_DEFAULT, "ShackCQ PL2303GC/KXUSB ready VID 0x067B PID 0x23A3 endpoints 83/02");
	return kIOReturnSuccess;
}

kern_return_t
IMPL(CP210xDriver, Stop)
{
	if (interfaceOpen && usbInterface != nullptr) (void)usbInterface->Close(this, 0);
	interfaceOpen = false;
	OSSafeReleaseNULL(bulkIn);
	OSSafeReleaseNULL(bulkOut);
	usbInterface = nullptr;
	os_log(OS_LOG_DEFAULT, "ShackCQ PL2303 service stopped");
	return Stop(provider, SUPERDISPATCH);
}

kern_return_t
IMPL(CP210xDriver, NewUserClient)
{
	if (!interfaceOpen || userClient == nullptr) return kIOReturnNotReady;
	IOService* client = nullptr;
	kern_return_t ret = Create(this, "UserClientProperties", &client);
	if (ret != kIOReturnSuccess) return ret;
	*userClient = OSDynamicCast(IOUserClient, client);
	if (*userClient == nullptr) {
		client->release();
		return kIOReturnError;
	}
	return kIOReturnSuccess;
}

bool
CP210xDriver::userClientReady()
{
	return interfaceOpen && bulkIn != nullptr && bulkOut != nullptr;
}

kern_return_t
CP210xDriver::userClientWrite(const void* bytes, uint32_t length)
{
	return userClientReady() ? writeBulk(bytes, length) : kIOReturnNotReady;
}

kern_return_t
CP210xDriver::userClientRead(void* bytes, uint32_t capacity, uint32_t* transferred)
{
	return userClientReady() ? readBulk(bytes, capacity, transferred) : kIOReturnNotReady;
}

kern_return_t
CP210xDriver::configurePL2303()
{
	const uint32_t baud = 38400;
	const uint8_t coding[7] = {
		static_cast<uint8_t>(baud), static_cast<uint8_t>(baud >> 8),
		static_cast<uint8_t>(baud >> 16), static_cast<uint8_t>(baud >> 24),
		0, 0, 8
	};
	kern_return_t ret = controlBytes(kClassInterfaceOut, kSetLineCoding, 0, kInterfaceNumber,
		coding, sizeof(coding));
	if (ret == kIOReturnSuccess)
		ret = controlValue(kClassInterfaceOut, kSetControlLineState, 0, kInterfaceNumber);
	if (ret == kIOReturnSuccess)
		ret = controlValue(kVendorDeviceOut, kHxnWriteRegister, kHxnPipeResetRegister, kHxnPipeResetBoth);
	return ret;
}

kern_return_t
CP210xDriver::controlValue(uint8_t requestType, uint8_t request, uint16_t value, uint16_t index)
{
	if (usbInterface == nullptr || !interfaceOpen) return kIOReturnNotReady;
	uint16_t transferred = 0;
	return usbInterface->DeviceRequest(requestType, request, value, index, 0, nullptr,
		&transferred, kControlTimeoutMs);
}

kern_return_t
CP210xDriver::controlBytes(uint8_t requestType, uint8_t request, uint16_t value, uint16_t index,
	const void* bytes, uint16_t length)
{
	if (usbInterface == nullptr || !interfaceOpen || bytes == nullptr || length == 0)
		return kIOReturnBadArgument;
	IOBufferMemoryDescriptor* buffer = nullptr;
	kern_return_t ret = IOBufferMemoryDescriptor::Create(kIOMemoryDirectionOut, length, 0, &buffer);
	if (ret != kIOReturnSuccess) return ret;
	if (buffer == nullptr) return kIOReturnNoMemory;
	IOAddressSegment range = {};
	ret = buffer->GetAddressRange(&range);
	if (ret == kIOReturnSuccess) {
		memcpy(reinterpret_cast<void*>(range.address), bytes, length);
		ret = buffer->SetLength(length);
	}
	uint16_t transferred = 0;
	if (ret == kIOReturnSuccess)
		ret = usbInterface->DeviceRequest(requestType, request, value, index, length, buffer,
			&transferred, kControlTimeoutMs);
	if (ret == kIOReturnSuccess && transferred != length) ret = kIOReturnUnderrun;
	buffer->release();
	return ret;
}

kern_return_t
CP210xDriver::writeBulk(const void* bytes, uint32_t length)
{
	if (bulkOut == nullptr || bytes == nullptr || length == 0 || length > 128) return kIOReturnBadArgument;
	IOBufferMemoryDescriptor* buffer = nullptr;
	kern_return_t ret = IOBufferMemoryDescriptor::Create(kIOMemoryDirectionOut, length, 0, &buffer);
	if (ret != kIOReturnSuccess) return ret;
	if (buffer == nullptr) return kIOReturnNoMemory;
	IOAddressSegment range = {};
	ret = buffer->GetAddressRange(&range);
	if (ret == kIOReturnSuccess) {
		memcpy(reinterpret_cast<void*>(range.address), bytes, length);
		ret = buffer->SetLength(length);
	}
	uint32_t transferred = 0;
	if (ret == kIOReturnSuccess) ret = bulkOut->IO(buffer, length, &transferred, kBulkTimeoutMs);
	if (ret == kIOReturnSuccess && transferred != length) ret = kIOReturnUnderrun;
	buffer->release();
	return ret;
}

kern_return_t
CP210xDriver::readBulk(void* bytes, uint32_t capacity, uint32_t* transferred)
{
	if (bulkIn == nullptr || bytes == nullptr || transferred == nullptr || capacity == 0)
		return kIOReturnBadArgument;
	*transferred = 0;
	IOBufferMemoryDescriptor* buffer = nullptr;
	kern_return_t ret = IOBufferMemoryDescriptor::Create(kIOMemoryDirectionIn, capacity, 0, &buffer);
	if (ret != kIOReturnSuccess) return ret;
	if (buffer == nullptr) return kIOReturnNoMemory;
	ret = buffer->SetLength(capacity);
	uint32_t count = 0;
	if (ret == kIOReturnSuccess) ret = bulkIn->IO(buffer, capacity, &count, kBulkTimeoutMs);
	if (ret == kIOReturnSuccess && count > capacity) ret = kIOReturnOverrun;
	if (ret == kIOReturnSuccess && count > 0) {
		IOAddressSegment range = {};
		ret = buffer->GetAddressRange(&range);
		if (ret == kIOReturnSuccess) memcpy(bytes, reinterpret_cast<const void*>(range.address), count);
	}
	if (ret == kIOReturnSuccess) *transferred = count;
	buffer->release();
	return ret;
}
