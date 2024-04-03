/* $Id$

 (c) Copyright, Real-Time Innovations, 2013-2016. 
 All rights reserved.
 No duplications, whole or partial, manual or electronic, may be made
 without express written permission.  Any such copies, or
 revisions thereof, must display this notice unaltered.
 This code contains trade secrets of Real-Time Innovations, Inc.
 
modification history
--------------------
10l,15aug06,kaj 64 bit: Convert APIs from long to DDS_Long, tokens to void*, add
                initializer for DDS_SEQUENCE_MEMBERS _elementPointersAllocation
10l,31jul06,vtg Fixed WinCE build
10e,11aug05,fcs Fixed QNX build
10e,11aug05,fcs Fixed Lynx build
10e,09aug05,fcs Created 

User Defines
------------

* Platform directive (one of the following):
    
    #define RTI_VXWORKS
    #define RTI_NETOS
    #define RTI_LYNX    
    #define RTI_QNX    
    #define RTI_OPENVMS    
    #define RTI_WIN32    
    #define RTI_UNIX    
    #define RTI_INTY  
    #define RTI_WINCE  
    
* If you want to export the type as part of a DLL is necessary to define the preprocessor directive:
  
    #define NDDSUSERDllExport __declspec( dllexport )
    
* Some architectures do not support �long long and �long double. In order to
  compile on all architectures, we defined character arrays that match the expected
  size of these types (8 bytes for long long, 16 bytes for long double). If you are
  using an architecture that does support these types, you can instruct NDDS to use
  the native types instead. In order to do so, use the following preprocessor directives:  
  
    #define RTI_CDR_SIZEOF_LONG_LONG 8
    #define RTI_CDR_SIZEOF_LONG_DOUBLE 16

*/

#ifndef ndds_standalone_type_h
#define ndds_standalone_type_h

#include <stdlib.h>
#include <string.h>

/* For LYNX is necessary to respect the inclusion order of <stdarg.h> and <stdio.h> */
#include <stdarg.h>  
#include <stdio.h>

#ifndef RTI_PRIVATE
  #define RTI_PRIVATE static
#endif
#define RTI_CODEGEN_STANDALONE

/* =============================================================================
 *  DLL Export
 * ===========================================================================*/

#ifndef NDDSUSERDllExport
#define NDDSUSERDllExport
#endif

/* =============================================================================
 *  DDS Log
 * ===========================================================================*/

#if (defined(RTI_VXWORKS) || defined(RTI_NETOS) || defined(RTI_LYNX) \
     || defined(RTI_OPENVMS))
#  ifndef vsnprintf
#    define vsnprintf(buf, count, fmt, ap) vsprintf(buf,fmt,ap)
#  endif
/* vsnprintf supportd by OS, but renamed */
#elif defined(RTI_WIN32) || defined(RTI_WINCE) || defined(RTI_INTIME)
#  ifndef vsnprintf
#    define vsnprintf _vsnprintf
#  endif
/* vsnprintf supportd by OS */
#elif defined(RTI_UNIX) || defined(RTI_INTY) || defined(RTI_QNX)
#else
#  error "Does your OS support vsnprintf?"
#endif

#if defined(RTI_WIN32) || defined(RTI_INTIME) || defined(RTI_INTY) || defined(__DCC__)
    #define RTI_FUNCTION_NAME __FUNCTION__
#else
    #define RTI_FUNCTION_NAME __func__
#endif

/* Max message size */
#define RTI_LOG_MSG_SIZE_MAX 1024

/*Uint 32 max size */
#define RTI_UINT32_MAX 4294967295U

/* Messages */
#define DDS_LOG_BAD_PARAMETER_s               "bad parameter: %s\n"
#define DDS_LOG_SEQUENCE_UNINITIALIZED        "!sequence initialized\n"
#define RTI_LOG_ASSERT_FAILURE_s              "!assert %s\n"
#define RTI_LOG_MALLOC_FAILURE_d              "!malloc %d bytes\n"
#define RTI_LOG_INSUFFICIENT_SPACE_FAILURE_dd "available space %d < %d\n"
#define DDS_LOG_SET_FAILURE_s                 "!set %s\n"
#define DDS_LOG_MEMORY_ALLOCATED_dd           "memory allocation: original %d, new %d\n"
#define DDS_LOG_SEQUENCE_NOT_OWNER            "!sequence does not own element buffer\n"
#define DDS_LOG_GET_FAILURE_s                 "!get %s\n"
#define DDS_LOG_OUT_OF_RESOURCES_s            "out of resources for %s\n"
#define RTI_LOG_ANY_FAILURE_s                 "!%s\n"


#define RTI_LOG_BIT_EXCEPTION     (0x00000002)

#define DDSLog_logWithFunctionName(INSTRUMENT_BIT, METHOD_NAME_IN, ...) \
        RTIDDSGENLogMessage_print( \
                (METHOD_NAME_IN), \
                __VA_ARGS__)

#define DDSLog_exception(...) \
        RTIDDSGENLogMessage_print( \
                RTI_FUNCTION_NAME,\
                __VA_ARGS__)

#define DDSLog_warn(...) \
        RTIDDSGENLogMessage_print( \
                RTI_FUNCTION_NAME,\
                __VA_ARGS__)

#define DDSLog_local(...) \
        RTIDDSGENLogMessage_print( \
                RTI_FUNCTION_NAME,\
                __VA_ARGS__)

#ifdef RTI_PRECONDITION_TEST 
  #define DDSLog_preconditionOnly( declaration )  declaration
#else
  #define DDSLog_preconditionOnly( declaration ) 
#endif

#ifdef __cplusplus
    extern "C" {
#endif

int RTIDDSGENLogMessage_print(const char *methodName, const void *message, ...);

#ifdef __cplusplus
    }     /* extern "C" */
#endif

/* =============================================================================
 *  OSAPI
 * ===========================================================================*/

/* Osapi Type definition */
  #ifndef RTIBool
  #define RTIBool int
  #endif

/* Boolean values */
  #ifndef RTI_FALSE
  #define RTI_FALSE (0)
  #endif

  #ifndef RTI_TRUE
  #define RTI_TRUE  (1)
  #endif

/*
 * Use this macro to remove warnings in situations where
 * a function is assigning its return value to a variable but
 * the variable is not being used anywhere. This will produce a
 * "set-but-unused variable warning".
 *
 * @param function The function returning a value which is not being used
 * @param type     The type of the expected return value of function
 *
 */
#define RTIOsapiUtility_unusedReturnValue(function, type) \
{                                                         \
    type unusedReturnValue;                               \
    unusedReturnValue = function;                         \
    (void)unusedReturnValue;                              \
} (void)0

#define RTIOsapiUtility_unusedParameter(parameter) (void) parameter

#ifdef RTI_PRECONDITION_TEST
  #define RTIOsapiUtility_unusedParameterInRelease(parameter__)
#else
  #define RTIOsapiUtility_unusedParameterInRelease(parameter__) \
    RTIOsapiUtility_unusedParameter(parameter__)
#endif

/* Osapi memory allocation macros */
#define RTIOsapiHeap_allocateArray(destPointer, elementCount, elementType)    \
    (*(destPointer) = (elementType *)calloc(elementCount, sizeof(elementType)))
    
#define RTIOsapiHeap_freeArray(dest) \
    if ((dest)!=NULL) free(dest)
    
#define RTIOsapiHeap_allocateString(stringStoragePointer, stringSize) \
    *(stringStoragePointer) = (char *)calloc(stringSize+1, 1)
    
#define RTIOsapiHeap_freeString(stringStorage) \
    if ((stringStorage) != NULL) free(stringStorage)
    
#define RTIOsapiMemory_zero(m, size) memset(m, 0, size)

#define RTIOsapiHeap_allocateStructure(destPointer, destType) \
    *(destPointer) = (destType *)calloc(1, sizeof(destType))
#define RTIOsapiHeap_freeStructure(dest) \
    if ((dest)!=NULL) free(dest)   
/* Functions */
#ifdef __cplusplus
    extern "C" {
#endif

RTIBool RTIOsapiHeap_reallocateString(char **stringStoragePointer, size_t stringSize);

#ifdef __cplusplus
    }     /* extern "C" */
#endif
    
/* =============================================================================
 *  CDR
 * ===========================================================================*/
 
#define RTI_CDR_CHAR_SIZE 1
#define RTI_CDR_OCTET_SIZE 1
#define RTI_CDR_WCHAR_SIZE 4
#define RTI_CDR_SHORT_SIZE 2
#define RTI_CDR_UNSIGNED_SHORT_SIZE 2
#define RTI_CDR_LONG_SIZE 4
#define RTI_CDR_UNSIGNED_LONG_SIZE 4
#define RTI_CDR_LONG_LONG_SIZE 8
#define RTI_CDR_UNSIGNED_LONG_LONG_SIZE 8
#define RTI_CDR_FLOAT_SIZE 4
#define RTI_CDR_DOUBLE_SIZE 8
#define RTI_CDR_LONG_DOUBLE_SIZE 16
#define RTI_CDR_BOOLEAN_SIZE 1
#define RTI_CDR_ENUM_SIZE 4

typedef enum {
    /* CDR char primitive type */
    RTI_CDR_CHAR_TYPE,
    /* CDR wchar primitive type */
    RTI_CDR_WCHAR_TYPE,
    /* CDR octet primitive type */
    RTI_CDR_OCTET_TYPE,
    /* CDR short primitive type */
    RTI_CDR_SHORT_TYPE,
    /* CDR unsigned short primitive type */
    RTI_CDR_UNSIGNED_SHORT_TYPE,
    /* CDR long primitive type */
    RTI_CDR_LONG_TYPE,
    /* CDR unsigned long primitive type */
    RTI_CDR_UNSIGNED_LONG_TYPE,
    /* CDR long long primitive type */
    RTI_CDR_LONG_LONG_TYPE,
    /* CDR unsigned long long primitive type */
    RTI_CDR_UNSIGNED_LONG_LONG_TYPE,
    /* CDR float primitive type */
    RTI_CDR_FLOAT_TYPE,
    /* CDR double primitive type */
    RTI_CDR_DOUBLE_TYPE,
    /* CDR long double primitive type */
    RTI_CDR_LONG_DOUBLE_TYPE,
    /* CDR boolean primitive type */
    RTI_CDR_BOOLEAN_TYPE,
    /* CDR enum primitive type */
    RTI_CDR_ENUM_TYPE
} RTICdrPrimitiveType;

#define RTI_CDR_LONG_LONG_SIZE 8
#define RTI_CDR_LONG_DOUBLE_SIZE 16

/* Primitive Types */
typedef char RTICdrChar;
typedef unsigned int RTICdrWchar;
typedef unsigned char RTICdrOctet;
typedef short RTICdrShort;
typedef unsigned short RTICdrUnsignedShort;
typedef int RTICdrLong;
typedef unsigned int RTICdrUnsignedLong;
typedef struct {
    char bytes[8];
} RTICdr8Byte;

typedef signed int  RTI_INT32;

#ifdef RTI_64BIT
    typedef signed long long  RTICdrLongLong;
    typedef unsigned long long RTICdrUnsignedLongLong;
#elif defined(_WIN32) || defined(_WIN32_WCE)
    typedef __int64 RTICdrLongLong;
    typedef unsigned __int64 RTICdrUnsignedLongLong;

#elif defined(RTI_SOL2)
    typedef signed long long  RTICdrLongLong;
    typedef unsigned long long  RTICdrUnsignedLongLong;
#elif defined(RTI_LINUX) || defined(RTI_DARWIN)
    typedef signed long long  RTICdrLongLong;
    typedef unsigned long long  RTICdrUnsignedLongLong;
#elif defined(RTI_AIX)
  typedef long long  RTICdrLongLong;
  typedef unsigned long long  RTICdrUnsignedLongLong;
#else
    typedef long long RTICdrLongLong;
    typedef unsigned long long RTICdrUnsignedLongLong;
#endif

typedef float RTICdrFloat;
typedef double RTICdrDouble;

#if (RTI_CDR_SIZEOF_LONG_DOUBLE == 16)
    typedef long double RTICdrLongDouble;
#else
    typedef struct RTICdrLongDouble {
        char bytes[16];
    } RTICdrLongDouble;
#endif

typedef unsigned char RTICdrBoolean;
typedef unsigned int  RTICdrEnum;

#define RTICdrType_getPrimitiveTypeSize(type)         \
  ((((type) == RTI_CDR_CHAR_TYPE) ||                  \
    ((type) == RTI_CDR_OCTET_TYPE) ||                 \
    ((type) == RTI_CDR_BOOLEAN_TYPE)) ? 1U :          \
   ((((type) == RTI_CDR_SHORT_TYPE) ||                \
     ((type) == RTI_CDR_UNSIGNED_SHORT_TYPE)) ? 2U :  \
    ((((type) == RTI_CDR_WCHAR_TYPE)||                \
      ((type) == RTI_CDR_LONG_TYPE) ||                \
      ((type) == RTI_CDR_UNSIGNED_LONG_TYPE) ||       \
      ((type) == RTI_CDR_FLOAT_TYPE) ||               \
      ((type) == RTI_CDR_ENUM_TYPE)) ? 4U :           \
     ((((type) == RTI_CDR_LONG_LONG_TYPE) ||          \
       ((type) == RTI_CDR_UNSIGNED_LONG_LONG_TYPE) || \
       ((type) == RTI_CDR_DOUBLE_TYPE)) ? 8U :        \
      ((type) == RTI_CDR_LONG_DOUBLE_TYPE) ? 16U : 0U))))

/* Initialize */

#define RTICdrType_init8Byte(value) \
  (((value) == NULL) ? RTI_FALSE : \
   (memset((value), 0, RTI_CDR_LONG_LONG_SIZE), RTI_TRUE))
   
#define RTICdrType_init16Byte(value) \
  (((value) == NULL) ? RTI_FALSE : \
   (memset((value), 0, RTI_CDR_LONG_DOUBLE_SIZE), RTI_TRUE))
   
#define RTICdrType_initChar(value) \
  (((value) == NULL) ? RTI_FALSE : \
   (*(value) = 0, RTI_TRUE))

#define RTICdrType_initWchar(value) \
  (((value) == NULL) ? RTI_FALSE : \
   (*(value) = 0, RTI_TRUE))

#define RTICdrType_initOctet(value) \
  (((value) == NULL) ? RTI_FALSE : \
   (*(value) = 0, RTI_TRUE))
   
#define RTICdrType_initShort(value) \
  (((value) == NULL) ? RTI_FALSE : \
   (*(value) = 0, RTI_TRUE))

#define RTICdrType_initUnsignedShort(value) \
  (((value) == NULL) ? RTI_FALSE : \
   (*(value) = 0, RTI_TRUE))
   
#define RTICdrType_initLong(value) \
  (((value) == NULL) ? RTI_FALSE : \
   (*(value) = 0, RTI_TRUE))

#define RTICdrType_initUnsignedLong(value) \
  (((value) == NULL) ? RTI_FALSE : \
   (*(value) = 0, RTI_TRUE))

#define RTICdrType_initLongLong(value) \
   RTICdrType_init8Byte((value))

#define RTICdrType_initUnsignedLongLong(value) \
   RTICdrType_init8Byte((value))

#define RTICdrType_initFloat(value) \
  (((value) == NULL) ? RTI_FALSE : \
   (*(value) = 0, RTI_TRUE))

#define RTICdrType_initDouble(value) \
  (((value) == NULL) ? RTI_FALSE : \
   (*(value) = 0, RTI_TRUE))

#define RTICdrType_initLongDouble(value) \
   RTICdrType_init16Byte((value))

#define RTICdrType_initBoolean(value) \
  (((value) == NULL) ? RTI_FALSE : \
   (*(value) = DDS_BOOLEAN_FALSE, RTI_TRUE))

#define RTICdrType_initEnum(value) \
  (((value) == NULL) ? RTI_FALSE : \
   (*(value) = 0, RTI_TRUE))

#define RTICdrType_initArray(value, length, elementSize) \
  (((value) == NULL) ? RTI_FALSE :                         \
   (memset((value), 0, ((elementSize) * (length))), RTI_TRUE))

#define RTICdrType_initArrayUnsafe(value__, length__, elementSize__) \
    memset((value__), 0, ((elementSize__) * (length__)))

#define RTICdrType_initString(value, maximumLength) \
   RTICdrType_initArray((value), (maximumLength), sizeof(RTICdrChar))

#define RTICdrType_initWstring(value, maximumLength)   \
   RTICdrType_initArray((value), (maximumLength), sizeof(RTICdrWchar))

/* Copy */

#define RTICdrType_copy8Byte(out, in) \
  (((out) == NULL || (in) == NULL) ? RTI_FALSE : \
   (memcpy((out), (in), RTI_CDR_LONG_LONG_SIZE), RTI_TRUE))

#define RTICdrType_copy16Byte(out, in) \
  (((out) == NULL || (in) == NULL) ? RTI_FALSE : \
   (memcpy((out), (in), RTI_CDR_LONG_DOUBLE_SIZE), RTI_TRUE))
      
#define RTICdrType_copyChar(out, in) \
  (((out) == NULL || (in) == NULL) ? RTI_FALSE : \
   (*(out) = *(in), RTI_TRUE))
   
#define RTICdrType_copyWchar(out, in) \
  (((out) == NULL || (in) == NULL) ? RTI_FALSE : \
   (*(out) = *(in), RTI_TRUE))

#define RTICdrType_copyOctet(out, in) \
  (((out) == NULL || (in) == NULL) ? RTI_FALSE : \
   (*(out) = *(in), RTI_TRUE))

#define RTICdrType_copyShort(out, in) \
  (((out) == NULL || (in) == NULL) ? RTI_FALSE : \
   (*(out) = *(in), RTI_TRUE))

#define RTICdrType_copyUnsignedShort(out, in) \
  (((out) == NULL || (in) == NULL) ? RTI_FALSE : \
   (*(out) = *(in), RTI_TRUE))

#define RTICdrType_copyLong(out, in) \
  (((out) == NULL || (in) == NULL) ? RTI_FALSE : \
   (*(out) = *(in), RTI_TRUE))

#define RTICdrType_copyUnsignedLong(out, in) \
  (((out) == NULL || (in) == NULL) ? RTI_FALSE : \
   (*(out) = *(in), RTI_TRUE))

#define RTICdrType_copyLongLong(out, in) \
   RTICdrType_copy8Byte(out,in)

#define RTICdrType_copyUnsignedLongLong(out, in) \
   RTICdrType_copy8Byte(out,in)

#define RTICdrType_copyFloat(out, in) \
  (((out) == NULL || (in) == NULL) ? RTI_FALSE : \
   (*(out) = *(in), RTI_TRUE))

#define RTICdrType_copyDouble(out, in) \
  (((out) == NULL || (in) == NULL) ? RTI_FALSE : \
   (*(out) = *(in), RTI_TRUE))

#define RTICdrType_copyLongDouble(out, in) \
   RTICdrType_copy16Byte(out,in)

#define RTICdrType_copyBoolean(out, in) \
  (((out) == NULL || (in) == NULL) ? RTI_FALSE : \
   (*(out) = *(in), RTI_TRUE))

#define RTICdrType_copyEnum(out, in) \
  (((out) == NULL || (in) == NULL) ? RTI_FALSE : \
   (*(out) = *(in), RTI_TRUE))
   
#define RTICdrType_copyArray(out, in, length, elementSize) \
  (((out) == NULL || (in) == NULL) ? RTI_FALSE :           \
   (memcpy((out), (in), ((elementSize) * (length))), RTI_TRUE))   

#define RTICdrType_copyString(out, in, maximumLength) \
   (((in) == NULL) ? RTI_FALSE : \
   ((strlen((in)) + 1 > (maximumLength)) ? RTI_FALSE : \
    RTICdrType_copyArray((out), (in), strlen((in)) + 1, \
                         sizeof(RTICdrChar))))
                         
#define RTICdrType_copyWstring(out, in, maximumLength) \
   (((in) == NULL) ? RTI_FALSE : \
   ((RTICdrType_getWstringLength((in)) + 1 > (maximumLength)) ? RTI_FALSE : \
    RTICdrType_copyArray((out), (in), RTICdrType_getWstringLength((in)) + 1, \
                         sizeof(RTICdrWchar))))
                         
/* Functions */
#ifdef __cplusplus
    extern "C" {
#endif

unsigned int RTICdrType_getWstringLength(const RTICdrWchar * str);
RTIBool RTICdrType_initStringArray(void *value, RTICdrUnsignedLong length,
                                   RTICdrUnsignedLong maximumStringLength,
                                   RTICdrPrimitiveType type);
RTIBool RTICdrType_copyStringArray(void *out, const void* in, RTICdrUnsignedLong length,
                                   RTICdrUnsignedLong maximumStringLength,RTICdrPrimitiveType type);
RTIBool RTICdrType_finalizeStringArray(void *value, RTICdrUnsignedLong length,RTICdrPrimitiveType type);                                   

RTIBool RTICdrType_copyStringEx(
        RTICdrChar **out, const RTICdrChar* in,
        RTICdrUnsignedLong maximumLength,
        RTIBool reallocate);

RTIBool RTICdrType_copyWstringEx(
    RTICdrWchar **out,
    const RTICdrWchar* in,
    RTICdrUnsignedLong maximumLength,
    RTIBool reallocate);

RTIBool RTICdrType_copyStringArrayEx(
    void *out, const void* in, RTICdrUnsignedLong length,
    RTICdrUnsignedLong maximumStringLength,RTICdrPrimitiveType type,
    RTIBool reallocate);

#ifdef __cplusplus
    }  /* extern "C" */
#endif
         
/* =============================================================================
 *  DDS
 * ===========================================================================*/
  
/* DDS Type definition */
typedef RTICdrChar DDS_Char;
typedef RTICdrWchar DDS_Wchar;
typedef RTICdrOctet DDS_Octet;
typedef RTICdrShort DDS_Short;
typedef RTICdrUnsignedShort DDS_UnsignedShort;
typedef RTICdrLong DDS_Long;
typedef RTICdrUnsignedLong DDS_UnsignedLong;
typedef RTICdrLongLong DDS_LongLong;
typedef RTICdrUnsignedLongLong DDS_UnsignedLongLong;
typedef RTICdrFloat DDS_Float;
typedef RTICdrDouble DDS_Double;
typedef RTICdrLongDouble DDS_LongDouble;
typedef RTICdrBoolean DDS_Boolean;
typedef RTICdrEnum DDS_Enum;

struct DDS_TypeAllocationParams_t {
    DDS_Boolean allocate_pointers;
    DDS_Boolean allocate_optional_members;
    DDS_Boolean allocate_memory;
    DDS_Boolean initialize_member_content;
};
extern const struct DDS_TypeAllocationParams_t
DDS_TYPE_ALLOCATION_PARAMS_DEFAULT;
struct DDS_TypeDeallocationParams_t {
    DDS_Boolean delete_pointers;
    DDS_Boolean delete_optional_members;
};
extern const struct DDS_TypeDeallocationParams_t
DDS_TYPE_DEALLOCATION_PARAMS_DEFAULT;
/* Boolean values */
#define DDS_BOOLEAN_TRUE    ((DDS_Boolean) 1)
#define DDS_BOOLEAN_FALSE   ((DDS_Boolean) 0)
 
/* Macros */
#define DDS_String_free(string) RTIOsapiHeap_freeString(string)
#define DDS_Wstring_free(string) RTIOsapiHeap_freeArray(string)

/* Functions */
#ifdef __cplusplus
    extern "C" {
#endif

char* DDS_String_alloc(size_t length);
char* DDS_String_dup(const char* str);
DDS_Wchar* DDS_Wstring_alloc(DDS_UnsignedLong length);
DDS_UnsignedLong DDS_Wstring_length(const DDS_Wchar* string);
char * DDS_String_replace(char** string_ptr, const char* new_value);
RTIBool DDS_StringSeq_initialize_string(char** string);
void DDS_StringSeq_finalize_string(char** string);
char** DDS_StringSeq_copy_string(char** self, const char** from);
DDS_Wchar* DDS_Wstring_replace(DDS_Wchar** string_ptr,const DDS_Wchar* new_value);
RTIBool DDS_WstringSeq_initialize_string(RTICdrWchar** wstring);
void DDS_WstringSeq_finalize_string(RTICdrWchar** wstring);
DDS_Wchar** DDS_WstringSeq_copy_string(DDS_Wchar** dst,const DDS_Wchar** src);
                                                                              
#ifdef __cplusplus
    }     /* extern "C" */
#endif
     
/* =============================================================================
 *  Sequence declaration macros
 * ===========================================================================*/
 
#ifdef __cplusplus
    extern "C" {
#endif/* and begin extern C again */

struct DDS_SeqElementTypeAllocationParams_t {
    DDS_Boolean allocate_pointers;
    DDS_Boolean allocate_optional_members;
    DDS_Boolean allocate_memory;
};
struct DDS_SeqElementTypeDeallocationParams_t {
    DDS_Boolean delete_pointers;
    DDS_Boolean delete_optional_members;
};
#define DDS_SeqElementTypeAllocationParams_from_type_alloc_settings( \
            self, typeAlloc) \
    (self)->allocate_pointers = (typeAlloc)->allocate_pointers; \
    (self)->allocate_optional_members = (typeAlloc)->allocate_optional_members; \
    (self)->allocate_memory = (typeAlloc)->allocate_memory
#define DDS_SeqElementTypeDeallocationParams_from_type_dealloc_settings( \
            self, typeDealloc) \
    (self)->delete_pointers = (typeDealloc)->delete_pointers; \
    (self)->delete_optional_members = (typeDealloc)->delete_optional_members
#define DDS_TypeAllocationParams_from_seq_type_alloc_settings( \
            self, typeAlloc) \
    (self)->allocate_pointers = (typeAlloc)->allocate_pointers; \
    (self)->allocate_optional_members = (typeAlloc)->allocate_optional_members; \
    (self)->allocate_memory = (typeAlloc)->allocate_memory
#define DDS_TypeDeallocationParams_from_seq_type_dealloc_settings( \
            self, typeDealloc) \
    (self)->delete_pointers = (typeDealloc)->delete_pointers; \
    (self)->delete_optional_members = (typeDealloc)->delete_optional_members
#define DDS_SEQUENCE_MEMBERS(TSeq, T) \
        DDS_Boolean _owned;               \
        T* _contiguous_buffer;        \
        T** _discontiguous_buffer;    \
        DDS_UnsignedLong _maximum;    \
        DDS_UnsignedLong _length;     \
        DDS_Long _sequence_init;          \
        void* _read_token1;   \
        void* _read_token2;   \
        struct DDS_SeqElementTypeAllocationParams_t _elementAllocParams; \
        struct DDS_SeqElementTypeDeallocationParams_t _elementDeallocParams;

#if defined(__cplusplus) && !defined(RTI_CXX11_STANDALONE)

/*
  Sequence dll export
*/
#define DDS_SEQUENCE_DLL_EXPORT NDDSUSERDllExport

/*
  C++98-only methods definition of the sequence methods
  Used in incarnating a \em generics implementation.
*/
#define DDS_SEQUENCE_METHODS(TSeq, T) \
bool element_pointers_allocation(bool allocatePointers);\
bool element_pointers_allocation();\
bool element_allocation_params( \
         DDS_TypeAllocationParams_t & params); \
DDS_TypeAllocationParams_t element_allocation_params(); \
bool element_deallocation_params( \
         DDS_TypeDeallocationParams_t & params); \
DDS_TypeDeallocationParams_t element_deallocation_params(); \
DDS_Long maximum() const; \
bool maximum(DDS_Long new_max); \
bool absolute_maximum(DDS_Long new_max); \
DDS_Long length() const; \
bool length(DDS_Long new_length); \
bool ensure_length(DDS_Long length, DDS_Long max); \
const T& operator[](DDS_Long i) const; \
T& operator[](DDS_Long i); \
bool has_ownership(); \
TSeq& operator=(const struct TSeq& src_seq); \
bool copy_no_alloc(const struct TSeq& src_seq); \
bool from_array(const T array[], DDS_Long length); \
bool loan_contiguous(T* buffer, DDS_Long new_length, DDS_Long new_max); \
bool loan_discontiguous(T** buffer, DDS_Long new_length, DDS_Long new_max); \
bool unloan(); \
T* get_contiguous_bufferI() const; \
T* get_contiguous_buffer() const; \
T** get_discontiguous_bufferI() const; \
void get_read_tokenI(void** token1,        \
                     void** token2) const; \
void set_read_tokenI(void* token1,         \
                     void* token2);        \
TSeq(DDS_Long max = 0); \
TSeq(const struct TSeq& seq); \
~TSeq();

#else /* if !defined(__cplusplus) */

/* Empty Sequence dll export variable
   Since for dds c sequence structure, there are no methods. Therefore, there is
   no need to export anything.
*/
#define DDS_SEQUENCE_DLL_EXPORT

/* Empty list of sequence methods.
*/
#define DDS_SEQUENCE_METHODS(TSeq, T)

#endif /* __cplusplus */

#define DDS_SEQUENCE(TSeq, T)   \
DDS_SEQUENCE_NO_GET(TSeq, T);\
NDDSUSERDllExport \
T TSeq ## _get(const struct TSeq* self, DDS_Long i)

#define DDS_SEQUENCE_NO_GET(TSeq, T)   \
struct DDS_SEQUENCE_DLL_EXPORT TSeq { \
DDS_SEQUENCE_MEMBERS(TSeq, T)   \
DDS_SEQUENCE_METHODS(TSeq, T)   \
};                              \
                                \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _get_element_pointers_allocation(                  \
    const struct TSeq* self);                                          \
                                                                       \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _set_element_pointers_allocation(                  \
    struct TSeq* self,                                                 \
    DDS_Boolean allocatePointers);                                     \
                                                                       \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _set_element_allocation_params(                    \
    struct TSeq* self,                                                 \
    const struct DDS_TypeAllocationParams_t * params);                 \
                                                                       \
NDDSUSERDllExport                                                      \
struct DDS_TypeAllocationParams_t TSeq ## _get_element_allocation_params(  \
    struct TSeq* self);                                                \
                                                                       \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _set_element_deallocation_params(                  \
    struct TSeq* self,                                                 \
    const struct DDS_TypeDeallocationParams_t * params);               \
                                                                       \
NDDSUSERDllExport                                                      \
struct DDS_TypeDeallocationParams_t TSeq ## _get_element_deallocation_params(  \
    struct TSeq* self);                                                \
                                                                       \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _initialize(struct TSeq* self);                    \
                                                                       \
NDDSUSERDllExport                                                      \
DDS_Long TSeq ## _get_maximum(const struct TSeq* self);                \
                                                                       \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _set_maximum(struct TSeq* self, DDS_Long new_max); \
    \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _set_absolute_maximum(struct TSeq* self, DDS_Long new_max); \
    \
NDDSUSERDllExport                                                      \
DDS_Long TSeq ## _get_length(const struct TSeq *self);                 \
                                                                       \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _set_length(struct TSeq *self, DDS_Long new_length);\
                                                                       \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _ensure_length(struct TSeq *self, DDS_Long length, \
                                                      DDS_Long max);   \
                                                                       \
NDDSUSERDllExport                                                      \
T* TSeq ## _get_reference(const struct TSeq* self, DDS_Long i);        \
                                                                       \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _copy_no_alloc(struct TSeq* self,                  \
                                    const struct TSeq* src);           \
                                                                       \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _copy_no_allocI(struct TSeq* self,                 \
                                    const struct TSeq* src);           \
                                                                       \
NDDSUSERDllExport                                                      \
struct TSeq* TSeq ## _copy(struct TSeq* self, const struct TSeq* src); \
                                                                       \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _from_array(struct TSeq* self, const T array[],    \
                                                   DDS_Long length);   \
                                                                       \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _loan_contiguous(struct TSeq* self, T* buffer,     \
                              DDS_Long new_length, DDS_Long new_max);  \
                                                                       \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _loan_discontiguous(struct TSeq* self,             \
                                       T** buffer, DDS_Long new_length,\
                                        DDS_Long new_max);             \
                                                                       \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _unloan(struct TSeq* self);                        \
                                                                       \
NDDSUSERDllExport                                                      \
T* TSeq ## _get_contiguous_bufferI(const struct TSeq* self);           \
                                                                       \
NDDSUSERDllExport                                                      \
T* TSeq ## _get_contiguous_buffer(const struct TSeq* self);            \
                                                                       \
NDDSUSERDllExport                                                      \
T** TSeq ## _get_discontiguous_bufferI(const struct TSeq* self);       \
                                                                       \
NDDSUSERDllExport                                                      \
void TSeq ## _get_read_tokenI(const struct TSeq* self,                 \
                              void** token1,                   \
                              void** token2);                  \
                                                                       \
NDDSUSERDllExport                                                      \
void TSeq ## _set_read_tokenI(struct TSeq* self,                       \
                              void* token1,                    \
                              void* token2);                   \
                                                                       \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _has_ownership(const struct TSeq* self);           \
                                                                       \
NDDSUSERDllExport                                                      \
DDS_Boolean TSeq ## _finalize(struct TSeq* self)


#define DDS_SEQUENCE_MAGIC_NUMBER       0x7344L


#define DDS_SEQUENCE_INITIALIZER { DDS_BOOLEAN_TRUE, NULL, NULL, 0, 0, \
                                 DDS_SEQUENCE_MAGIC_NUMBER, NULL, NULL, \
                                 {DDS_BOOLEAN_TRUE, DDS_BOOLEAN_FALSE, DDS_BOOLEAN_TRUE}, \
                                 {DDS_BOOLEAN_TRUE, DDS_BOOLEAN_TRUE}}

#ifdef __cplusplus
    }   /* extern "C" */
#endif

/* =============================================================================
 *  Primitive Sequence Declaration
 * ===========================================================================*/
 
#ifdef __cplusplus
    extern "C" {
#endif/* and begin extern C again */
 
DDS_SEQUENCE(DDS_CharSeq, DDS_Char);
DDS_SEQUENCE(DDS_WcharSeq, DDS_Wchar);
DDS_SEQUENCE(DDS_OctetSeq, DDS_Octet);
DDS_SEQUENCE(DDS_ShortSeq, DDS_Short);
DDS_SEQUENCE(DDS_UnsignedShortSeq, DDS_UnsignedShort);
DDS_SEQUENCE(DDS_LongSeq, DDS_Long);
DDS_SEQUENCE(DDS_UnsignedLongSeq, DDS_UnsignedLong);
DDS_SEQUENCE(DDS_LongLongSeq, DDS_LongLong);
DDS_SEQUENCE(DDS_UnsignedLongLongSeq, DDS_UnsignedLongLong);
DDS_SEQUENCE(DDS_FloatSeq, DDS_Float);
DDS_SEQUENCE(DDS_DoubleSeq, DDS_Double);
DDS_SEQUENCE(DDS_LongDoubleSeq, DDS_LongDouble);
DDS_SEQUENCE(DDS_BooleanSeq, DDS_Boolean);
DDS_SEQUENCE(DDS_StringSeq, DDS_Char*);
DDS_SEQUENCE(DDS_WstringSeq, DDS_Wchar*);

#ifdef __cplusplus
    }   /* extern "C" */
#endif

/* =============================================================================
 *  Exception
 * ===========================================================================*/
typedef enum {
	/*e \dref_ReturnCode_t_RETCODE_OK
		*/
	DDS_RETCODE_OK = 0,

	/*e \dref_ReturnCode_t_RETCODE_ERROR
		*/
	DDS_RETCODE_ERROR = 1,

	/*e \dref_ReturnCode_t_RETCODE_UNSUPPORTED
		*/
	DDS_RETCODE_UNSUPPORTED = 2,

	/*e \dref_ReturnCode_t_RETCODE_BAD_PARAMETER
		*/
	DDS_RETCODE_BAD_PARAMETER = 3,

	/*e \dref_ReturnCode_t_RETCODE_PRECONDITION_NOT_MET
		*/
	DDS_RETCODE_PRECONDITION_NOT_MET = 4,

	/*e \dref_ReturnCode_t_RETCODE_OUT_OF_RESOURCES
		*/
	DDS_RETCODE_OUT_OF_RESOURCES = 5,

	/*e \dref_ReturnCode_t_RETCODE_NOT_ENABLED
		*/
	DDS_RETCODE_NOT_ENABLED = 6,

	/*e \dref_ReturnCode_t_RETCODE_IMMUTABLE_POLICY
		*/
	DDS_RETCODE_IMMUTABLE_POLICY = 7,

	/*e \dref_ReturnCode_t_RETCODE_INCONSISTENT_POLICY
		*/
	DDS_RETCODE_INCONSISTENT_POLICY = 8,

	/*e \dref_ReturnCode_t_RETCODE_ALREADY_DELETED
		*/
	DDS_RETCODE_ALREADY_DELETED = 9,

	/*e \dref_ReturnCode_t_RETCODE_TIMEOUT
		*/
	DDS_RETCODE_TIMEOUT = 10,

	/*e \dref_ReturnCode_t_RETCODE_NO_DATA
		*/
	DDS_RETCODE_NO_DATA = 11,

	/*e \dref_ReturnCode_t_RETCODE_ILLEGAL_OPERATION
		*/
	DDS_RETCODE_ILLEGAL_OPERATION = 12,

	/*e \dref_ReturnCode_t_RETCODE_NOT_ALLOWED_BY_SECURITY
		* Currently unused because security checks happen during create, not
		* enable, and create doesn't return a ReturnCode (SEC-731).
		*/
	DDS_RETCODE_NOT_ALLOWED_BY_SECURITY = 1000
} DDS_ReturnCode_t;

typedef enum {
	/*e \dref_ExceptionCode_t_NO_EXCEPTION_CODE
	 */
	DDS_NO_EXCEPTION_CODE = 0,
	/*e \dref_ExceptionCode_t_USER_EXCEPTION_CODE
	 */
	DDS_USER_EXCEPTION_CODE = 1,
	/*e \dref_ExceptionCode_t_SYSTEM_EXCEPTION_CODE
	 */
	DDS_SYSTEM_EXCEPTION_CODE = 2,
	/*e \dref_ExceptionCode_t_BAD_PARAM_SYSTEM_EXCEPTION_CODE
	 */
	DDS_BAD_PARAM_SYSTEM_EXCEPTION_CODE = 3,
	/*e \dref_ExceptionCode_t_NO_MEMORY_SYSTEM_EXCEPTION_CODE
	 */
	DDS_NO_MEMORY_SYSTEM_EXCEPTION_CODE = 4,
	/*e \dref_ExceptionCode_t_BAD_TYPECODE_SYSTEM_EXCEPTION_CODE
	 */
	DDS_BAD_TYPECODE_SYSTEM_EXCEPTION_CODE = 5,
	/*e \dref_ExceptionCode_t_BADKIND_USER_EXCEPTION_CODE
	 */
	DDS_BADKIND_USER_EXCEPTION_CODE = 6,
	/*e \dref_ExceptionCode_t_BOUNDS_USER_EXCEPTION_CODE
	 */
	DDS_BOUNDS_USER_EXCEPTION_CODE = 7,
	/*e \dref_ExceptionCode_t_IMMUTABLE_TYPECODE_SYSTEM_EXCEPTION_CODE
	 */
	DDS_IMMUTABLE_TYPECODE_SYSTEM_EXCEPTION_CODE = 8,
	/*e \dref_ExceptionCode_t_BAD_MEMBER_NAME_USER_EXCEPTION_CODE
	 */
	DDS_BAD_MEMBER_NAME_USER_EXCEPTION_CODE = 9,
	/*e \dref_ExceptionCode_t_BAD_MEMBER_ID_USER_EXCEPTION_CODE
	 */
	DDS_BAD_MEMBER_ID_USER_EXCEPTION_CODE = 10

} DDS_ExceptionCode_t;

                                               
#endif /* ndds_standalone_type_h */
