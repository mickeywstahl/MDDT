/* $Id$
 (c) Copyright, Real-Time Innovations, 2013-2016.
 All rights reserved.

 No duplications, whole or partial, manual or electronic, may be made
 without express written permission.  Any such copies, or
 revisions thereof, must display this notice unaltered.
 This code contains trade secrets of Real-Time Innovations, Inc.

modification history
--------------------
1.0,18feb13,acr Copied over from prototype and fixed issues
============================================================================= */


#ifdef NDDS_STANDALONE_TYPE
    #include "ndds_standalone_type.h"
#else
    #include "log/log_makeheader.h"

    #ifndef osapi_heap_h
        #include "osapi/osapi_heap.h"
    #endif

    #ifndef advlog_logger_h
        #include "advlog/advlog_logger.h"
    #endif
#endif

#pragma begin_Peer
#include <rti/core/Exception.hpp>
#pragma end_Peer

#include <dds/core/String.hpp>

// --- Precondition checks: ---------------------------------------------------

#pragma begin_Peer

#define RTI_STRINGIFY(x) #x
#define RTI_TOSTRING(x) RTI_STRINGIFY(x)

#if RTI_PRECONDITION_TEST
#define RTI_ASSERT_PRECONDITION(EXPRESSION)                                   \
    if (!(EXPRESSION)) {                                                      \
        throw dds::core::PreconditionNotMetError(                             \
            "Precondition failed: " # EXPRESSION " (" __FILE__ ":" RTI_TOSTRING(__LINE__) ")");                            \
    }
#else
#define RTI_ASSERT_PRECONDITION(EXPRESSION)
#endif

#pragma end_Peer

namespace rti { namespace core {
dds::core::string get_last_error_messages();
} }

// ----------------------------------------------------------------------------

namespace dds { namespace core {

// --- Exception: -------------------------------------------------------------

Exception::Exception() { }

Exception::~Exception() throw() { }

// --- Error: -----------------------------------------------------------------

Error::Error() : std::exception()
{
}

Error::Error(const std::string& msg)
    : message_(msg)
{
}

Error::Error(const Error& src) : std::exception(src), message_(src.message_)
{
}

Error& Error::operator=(const Error& src)
{
    message_ = src.message_;
    std::exception::operator=(src);
    return *this;
}

Error::~Error() throw()
{
}

const char* Error::what() const throw()
{
    return message_.c_str();
}

// --- InvalidArgumentError: --------------------------------------------------

InvalidArgumentError::InvalidArgumentError(const std::string& msg)
        : std::invalid_argument(std::string("Invalid argument error: ") + msg)
{ }

InvalidArgumentError::InvalidArgumentError(const InvalidArgumentError& src)
    : std::invalid_argument(src)
{
}

InvalidArgumentError& InvalidArgumentError::operator=(
    const InvalidArgumentError& src)
{
    std::invalid_argument::operator=(src);
    return *this;
}


InvalidArgumentError::~InvalidArgumentError() throw ()
{
}

const char* InvalidArgumentError::what() const throw ()
{
    return std::logic_error::what();
}

// --- AlreadyClosedError: ----------------------------------------------------

AlreadyClosedError::AlreadyClosedError(const std::string& msg)
    : std::logic_error(std::string("Already closed error: ") + msg)
{
}

AlreadyClosedError::AlreadyClosedError(const AlreadyClosedError& src)
    : std::logic_error(src)
{
}

AlreadyClosedError& AlreadyClosedError::operator=(const AlreadyClosedError& src)
{
    std::logic_error::operator=(src);
    return *this;
}

AlreadyClosedError::~AlreadyClosedError() throw ()
{
}


const char* AlreadyClosedError::what() const throw ()
{
    return std::logic_error::what();
}

// --- IllegalOperationError: -------------------------------------------------

IllegalOperationError::IllegalOperationError(const std::string& msg)
    : std::logic_error(std::string("Illegal operation error: ") + msg)
{
}

IllegalOperationError::IllegalOperationError(const IllegalOperationError& src)
    : std::logic_error(src)
{
}

IllegalOperationError& IllegalOperationError::operator=(
    const IllegalOperationError& src)
{
    std::logic_error::operator=(src);
    return *this;
}

IllegalOperationError::~IllegalOperationError() throw ()
{
}


const char* IllegalOperationError::what() const throw ()
{
    return std::logic_error::what();
}

// --- NotAllowedBySecurityError: ---------------------------------------------

NotAllowedBySecurityError::NotAllowedBySecurityError(const std::string& msg)
    : std::logic_error(std::string("Not allowed by security error: ") + msg)
{
}

NotAllowedBySecurityError::NotAllowedBySecurityError(
        const NotAllowedBySecurityError& src)
    : std::logic_error(src)
{
}

NotAllowedBySecurityError& NotAllowedBySecurityError::operator=(
        const NotAllowedBySecurityError& src)
{
    std::logic_error::operator=(src);
    return *this;
}

NotAllowedBySecurityError::~NotAllowedBySecurityError() throw ()
{
}

const char* NotAllowedBySecurityError::what() const throw ()
{
    return std::logic_error::what();
}

// --- NotEnabledError: -------------------------------------------------------

NotEnabledError::NotEnabledError(const std::string& msg)
    : std::logic_error(std::string("Not enabled error: ") + msg)
{
}

NotEnabledError::NotEnabledError(const NotEnabledError& src)
    : std::logic_error(src)
{
}

NotEnabledError& NotEnabledError::operator=(const NotEnabledError& src)
{
    std::logic_error::operator=(src);
    return *this;
}


NotEnabledError::~NotEnabledError() throw ()
{
}

const char* NotEnabledError::what() const throw ()
{
    return std::logic_error::what();
}

// --- PreconditionNotMetError: -----------------------------------------------

PreconditionNotMetError::PreconditionNotMetError(const std::string& msg)
    : std::logic_error(std::string("Precondition not met error: ") + msg)
{
}

PreconditionNotMetError::PreconditionNotMetError(
    const PreconditionNotMetError& src)
    : std::logic_error(src)
{
}

PreconditionNotMetError& PreconditionNotMetError::operator=(
    const PreconditionNotMetError& src)
{
    std::logic_error::operator=(src);
    return *this;
}


PreconditionNotMetError::~PreconditionNotMetError() throw ()
{
}

const char* PreconditionNotMetError::what() const throw ()
{
    return std::logic_error::what();
}

// --- ImmutablePolicyError: --------------------------------------------------

ImmutablePolicyError::ImmutablePolicyError(const std::string& msg)
    : std::logic_error(std::string("Immutable policy error: ") + msg)
{
}

ImmutablePolicyError::ImmutablePolicyError(const ImmutablePolicyError& src)
    : std::logic_error(src)
{
}

ImmutablePolicyError& ImmutablePolicyError::operator=(
    const ImmutablePolicyError& src)
{
    std::logic_error::operator=(src);
    return *this;
}


ImmutablePolicyError::~ImmutablePolicyError() throw ()
{
}

const char* ImmutablePolicyError::what() const throw ()
{
    return std::logic_error::what();
}

// --- InconsistentPolicyError: -----------------------------------------------

InconsistentPolicyError::InconsistentPolicyError(const std::string& msg)
    : std::logic_error(std::string("Inconsistent policy error: ") + msg)
{
}

InconsistentPolicyError::InconsistentPolicyError(
    const InconsistentPolicyError& src)
    : std::logic_error(src)
{
}

InconsistentPolicyError& InconsistentPolicyError::operator=(
    const InconsistentPolicyError& src)
{
    std::logic_error::operator=(src);
    return *this;
}

InconsistentPolicyError::~InconsistentPolicyError() throw ()
{
}

const char* InconsistentPolicyError::what() const throw ()
{
    return std::logic_error::what();
}

// --- InvalidDataError: ------------------------------------------------------

InvalidDataError::InvalidDataError(const std::string& msg)
    : std::logic_error(std::string("Invalid data error: ") + msg)
{
}

InvalidDataError::InvalidDataError(const InvalidDataError& src)
    : std::logic_error(src)
{
}

InvalidDataError& InvalidDataError::operator=(const InvalidDataError& src)
{
    std::logic_error::operator=(src);
    return *this;
}

InvalidDataError::~InvalidDataError() throw ()
{
}

const char* InvalidDataError::what() const throw ()
{
    return std::logic_error::what();
}

// --- InvalidDowncastError: --------------------------------------------------

InvalidDowncastError::InvalidDowncastError(const std::string& msg)
    : std::runtime_error(std::string("Invalid downcast error: ") + msg)
{
}

InvalidDowncastError& InvalidDowncastError::operator=(
    const InvalidDowncastError& src)
{
    std::runtime_error::operator=(src);
    return *this;
}


InvalidDowncastError::~InvalidDowncastError() throw ()
{
}

InvalidDowncastError::InvalidDowncastError(const InvalidDowncastError& src)
    : Exception(src), std::runtime_error(src)
{
}

const char* InvalidDowncastError::what() const throw ()
{
    return std::runtime_error::what();
}

// --- NullReferenceError: ----------------------------------------------------

NullReferenceError::NullReferenceError(const std::string& msg)
    : std::runtime_error(std::string("Null reference error: ") + msg)
{
}

NullReferenceError::NullReferenceError(const NullReferenceError& src)
    : std::runtime_error(src)
{
}

NullReferenceError& NullReferenceError::operator=(const NullReferenceError& src)
{
    std::runtime_error::operator=(src);
    return *this;
}

NullReferenceError::~NullReferenceError() throw ()
{
}

const char* NullReferenceError::what() const throw ()
{
    return std::runtime_error::what();
}

// --- UnsupportedError: ------------------------------------------------------

UnsupportedError::UnsupportedError(const std::string& msg)
    : std::logic_error(std::string("Unsupported error: ") + msg)
{
}

UnsupportedError::UnsupportedError(const UnsupportedError& src)
    : Exception(src), std::logic_error(src)
{

}

UnsupportedError& UnsupportedError::operator=(const UnsupportedError& src)
{
    std::logic_error::operator=(src);
    return *this;
}

UnsupportedError::~UnsupportedError() throw ()
{
}

const char* UnsupportedError::what() const throw ()
{
    return std::logic_error::what();
}

// --- TimeoutError: ----------------------------------------------------------

TimeoutError::TimeoutError(const std::string& msg)
    : std::runtime_error(std::string("Timeout error: ") + msg)
{
}

TimeoutError::TimeoutError(const TimeoutError& src)
    : std::runtime_error(src)
{
}

TimeoutError& TimeoutError::operator=(const TimeoutError& src)
{
    std::runtime_error::operator=(src);
    return *this;
}

TimeoutError::~TimeoutError() throw()
{
}

const char* TimeoutError::what() const throw()
{
    return std::runtime_error::what();
}

// --- OutOfResourcesError: ---------------------------------------------------

OutOfResourcesError::OutOfResourcesError(const std::string& msg)
    : std::runtime_error(std::string("Out of resources error: ") + msg)
{
}

OutOfResourcesError::OutOfResourcesError(const OutOfResourcesError& src)
    : std::runtime_error(src)
{
}

OutOfResourcesError& OutOfResourcesError::operator=(
    const OutOfResourcesError& src)
{
    std::runtime_error::operator=(src);
    return *this;
}


OutOfResourcesError::~OutOfResourcesError() throw()
{
}

const char* OutOfResourcesError::what() const throw()
{
    return std::runtime_error::what();
}

} } // namespace dds::core

// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------


namespace rti { namespace core { namespace detail {

#define RETCODE_ERROR_DEFAULT_MESSAGE_LENGTH 1024

dds::core::string get_last_error_messages()
{
    // With standalone we do not have access to the ADVLOGLogger
	#ifdef NDDS_STANDALONE_TYPE
		return "";
	#else
		RTI_INT32 size = 0;
		
		/* Get expected length */
		ADVLOGLogger_getLastErrorMessages(NULL, NULL, &size);
		if (size == 0) {
			return "";
		}

		dds::core::string error_message(size);
		
		ADVLOGLogger_getLastErrorMessages(
		   NULL, const_cast<char*>(error_message.c_str()), &size);

		/* Now clear the message queue */
		ADVLOGLogger_emptyMessageInfoQueue(NULL);

		return error_message;
	#endif
}

void throw_return_code_ex(DDS_ReturnCode_t retcode, const char * message)
{
    using namespace dds::core; // for the Exception classes

    std::string error_messages;

    if (retcode == DDS_RETCODE_ERROR || 
        retcode == DDS_RETCODE_BAD_PARAMETER ||
        retcode == DDS_RETCODE_PRECONDITION_NOT_MET ||
        retcode == DDS_RETCODE_OUT_OF_RESOURCES ||
        retcode == DDS_RETCODE_IMMUTABLE_POLICY ||
        retcode == DDS_RETCODE_INCONSISTENT_POLICY ||
        retcode == DDS_RETCODE_NOT_ALLOWED_BY_SECURITY) {
        error_messages = std::string(get_last_error_messages()) + message;
    }

    switch (retcode) {
    case DDS_RETCODE_ERROR:
        throw dds::core::Error(error_messages);
    case DDS_RETCODE_UNSUPPORTED:
        throw UnsupportedError(message);
    case DDS_RETCODE_BAD_PARAMETER:
        throw InvalidArgumentError(error_messages);
    case DDS_RETCODE_PRECONDITION_NOT_MET:
        throw PreconditionNotMetError(error_messages);
    case DDS_RETCODE_OUT_OF_RESOURCES:
        throw OutOfResourcesError(error_messages);
    case DDS_RETCODE_NOT_ENABLED:
        throw NotEnabledError(message);
    case DDS_RETCODE_IMMUTABLE_POLICY:
        throw ImmutablePolicyError(error_messages);
    case DDS_RETCODE_INCONSISTENT_POLICY:
        throw InconsistentPolicyError(error_messages);
    case DDS_RETCODE_ALREADY_DELETED:
        throw AlreadyClosedError(message);
    case DDS_RETCODE_TIMEOUT:
        throw TimeoutError(message);
    case DDS_RETCODE_ILLEGAL_OPERATION:
        throw IllegalOperationError(message);
    case DDS_RETCODE_NOT_ALLOWED_BY_SECURITY:
        throw NotAllowedBySecurityError(error_messages);

    /*case DDS_RETCODE_INVALID_DATA:
        throw InvalidDataError(message);

     */
    default:
        throw dds::core::Error();
    }
}

void throw_get_entity_ex(const char * entity_name)
{
    throw dds::core::Error(
            std::string(get_last_error_messages()) + 
            "Failed to get " + std::string(entity_name));
}

void throw_create_entity_ex(const char * entity_name)
{
    throw dds::core::Error("Failed to create " + std::string(entity_name));
}

void throw_tc_ex(DDS_ExceptionCode_t ex, const char * message)
{
    switch (ex) {
    case DDS_BAD_MEMBER_NAME_USER_EXCEPTION_CODE:
        throw dds::core::InvalidArgumentError(
            std::string("Invalid member name: ") + message);
    case DDS_BAD_MEMBER_ID_USER_EXCEPTION_CODE:
        throw dds::core::InvalidArgumentError(
            std::string("Invalid member id: ") + message);
    case DDS_BADKIND_USER_EXCEPTION_CODE:
        throw dds::core::InvalidArgumentError(
            std::string("Invalid type kind: ") + message);
    case DDS_BOUNDS_USER_EXCEPTION_CODE:
        throw dds::core::InvalidArgumentError(
            std::string("Invalid member bounds: ") + message);
    case DDS_USER_EXCEPTION_CODE:
        throw dds::core::PreconditionNotMetError(message);
    case DDS_SYSTEM_EXCEPTION_CODE:
    case DDS_BAD_PARAM_SYSTEM_EXCEPTION_CODE:
        throw dds::core::InvalidArgumentError(
            std::string("Invalid argument error: ") + message);    
    case DDS_NO_MEMORY_SYSTEM_EXCEPTION_CODE:
    case DDS_BAD_TYPECODE_SYSTEM_EXCEPTION_CODE:
    case DDS_IMMUTABLE_TYPECODE_SYSTEM_EXCEPTION_CODE:
    default:
        throw dds::core::Error(message);
    }
}

} } } // namespace rti::core::detail



