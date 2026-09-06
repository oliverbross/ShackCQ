#include <DriverKit/OSData.h>
#include <DriverKit/IOLib.h>
#include <os/log.h>

#define KXUSBUserClient_DECLARE_IVARS IOService* hardwareService;
#include "KXUSBUserClient.h"
#include "CP210xDriver.h"

namespace {
constexpr uint32_t kMaximumCATPacket = 512;

enum ExternalMethodSelector : uint64_t {
    kExternalStatus = 0,
    kExternalWrite = 1,
    kExternalRead = 2,
};
}

kern_return_t IMPL(KXUSBUserClient, Start)
{
    hardwareService = nullptr;

    kern_return_t ret = Start(provider, SUPERDISPATCH);
    if (ret != kIOReturnSuccess) {
        return ret;
    }

    CP210xDriver* device = OSDynamicCast(CP210xDriver, provider);
    if (device == nullptr || !device->userClientReady()) {
        (void)Stop(provider, SUPERDISPATCH);
        return kIOReturnNotReady;
    }

    hardwareService = device;
    hardwareService->retain();
    os_log(OS_LOG_DEFAULT, "ShackCQ KXUSB user client opened");
    return kIOReturnSuccess;
}

kern_return_t IMPL(KXUSBUserClient, Stop)
{
    if (hardwareService != nullptr) {
        hardwareService->release();
        hardwareService = nullptr;
    }
    return Stop(provider, SUPERDISPATCH);
}

kern_return_t KXUSBUserClient::ExternalMethod(uint64_t selector,
    IOUserClientMethodArguments* arguments,
    const IOUserClientMethodDispatch* dispatch,
    OSObject* target,
    void* reference)
{
    (void)dispatch;
    (void)target;
    (void)reference;

    if (arguments == nullptr) {
        return kIOReturnBadArgument;
    }

    CP210xDriver* device = OSDynamicCast(CP210xDriver, hardwareService);
    if (device == nullptr || !device->userClientReady()) {
        return kIOReturnNotReady;
    }

    switch (selector) {
    case kExternalStatus:
        if (arguments->scalarOutputCount < 1) {
            return kIOReturnBadArgument;
        }
        arguments->scalarOutput[0] = 1;
        arguments->scalarOutputCount = 1;
        return kIOReturnSuccess;

    case kExternalWrite: {
        if (arguments->structureInput == nullptr) {
            return kIOReturnBadArgument;
        }
        const uint64_t length = arguments->structureInput->getLength();
        if (length == 0 || length > kMaximumCATPacket) {
            return kIOReturnBadArgument;
        }
        const void* bytes = arguments->structureInput->getBytesNoCopy();
        if (bytes == nullptr) {
            return kIOReturnBadArgument;
        }
        return device->userClientWrite(bytes, static_cast<uint32_t>(length));
    }

    case kExternalRead: {
        if (arguments->structureOutputMaximumSize == 0) {
            return kIOReturnBadArgument;
        }
        const uint32_t capacity = static_cast<uint32_t>(
            arguments->structureOutputMaximumSize > kMaximumCATPacket
                ? kMaximumCATPacket
                : arguments->structureOutputMaximumSize);
        uint8_t bytes[kMaximumCATPacket] = {};
        uint32_t length = 0;
        kern_return_t ret = device->userClientRead(bytes, capacity, &length);
        if (ret == kIOReturnTimeout) {
            ret = kIOReturnSuccess;
            length = 0;
        }
        if (ret != kIOReturnSuccess) {
            return ret;
        }
        arguments->structureOutput = OSData::withBytes(bytes, length);
        if (arguments->structureOutput == nullptr) {
            return kIOReturnNoMemory;
        }
        arguments->structureOutputMaximumSize = length;
        return kIOReturnSuccess;
    }

    default:
        return kIOReturnUnsupported;
    }
}
