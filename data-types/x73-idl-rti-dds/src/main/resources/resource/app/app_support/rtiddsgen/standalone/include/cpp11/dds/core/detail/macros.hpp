#ifndef OMG_DDS_CORE_DETAIL_MACROS_HPP_
#define OMG_DDS_CORE_DETAIL_MACROS_HPP_

/* Copyright 2010, Object Management Group, Inc.
 * Copyright 2010, PrismTech, Corp.
 * Copyright 2010, Real-Time Innovations, Inc.
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <iostream>
#include <functional> // for std::hash
#include <type_traits>
#include <string.h>
#ifdef NDDS_STANDALONE_TYPE
	#define RTI_CXX11_STANDALONE
    #include "ndds_standalone_type.h"
#else
    #include "osapi/osapi_platform.h"
#endif

#ifdef NDDS_DLL_VARIABLE
    #define RTI_dds_cpp2_DLL_VARIABLE
    #define RTI_dds_cpp_DLL_VARIABLE
    #define RTI_dds_c_DLL_VARIABLE
    #define RTI_disc_DLL_VARIABLE
    #define RTI_pres_DLL_VARIABLE
    #define RTI_property_DLL_VARIABLE
    #define RTI_writer_history_DLL_VARIABLE
    #define RTI_commend_DLL_VARIABLE
    #define RTI_mig_DLL_VARIABLE
    #define RTI_netio_DLL_VARIABLE
    #define RTI_netio_cap_DLL_VARIABLE
    #define RTI_transport_DLL_VARIABLE
    #define RTI_event_DLL_VARIABLE
    #define RTI_cdr_DLL_VARIABLE
    #define RTI_reda_DLL_VARIABLE
    #define RTI_dl_driver_DLL_VARIABLE
    #define RTI_osapi_DLL_VARIABLE
    #define RTI_clock_DLL_VARIABLE
    #define RTI_log_DLL_VARIABLE
#endif
  
// DLL Export Macros
// This is defined for 32 and 64 bit Windows
#if defined(RTI_WIN32) || defined(RTI_WINCE) || defined(RTI_INTIME)
  #if defined(RTI_dds_cpp2_DLL_EXPORT)
    #  define OMG_DDS_API_DETAIL __declspec( dllexport )
  #else
    #  define OMG_DDS_API_DETAIL
  #endif

  #if defined(RTI_dds_cpp2_DLL_VARIABLE) 
    #if defined(RTI_dds_cpp2_DLL_EXPORT)
      #define OMG_DDS_API_VARIABLE_DETAIL __declspec( dllexport )
      #define OMG_DDS_API_CLASS_VARIABLE_DETAIL
    #else
      #define OMG_DDS_API_VARIABLE_DETAIL __declspec( dllimport )
      #define OMG_DDS_API_CLASS_VARIABLE_DETAIL __declspec( dllimport )
    #endif /* RTI_dds_cpp2_DLL_EXPORT */
  #else 
    #define OMG_DDS_API_VARIABLE_DETAIL
    #define OMG_DDS_API_CLASS_VARIABLE_DETAIL
  #endif /* RTI_dds_cpp2_DLL_VARIABLE */
#else
  #define OMG_DDS_API_DETAIL
  #define OMG_DDS_API_VARIABLE_DETAIL
  #define OMG_DDS_API_CLASS_VARIABLE_DETAIL
#endif /* RTI_WIN32 || RTI_WINCE || RTI_INTIME */

#ifndef NDDS_STANDALONE_TYPE
    #include "dds_c/dds_c_string.h"
    #include "dds_c/dds_c_log_impl.h"
#endif

// == Constants
#define OMG_DDS_DEFAULT_STATE_BIT_COUNT_DETAIL (size_t)16
#define OMG_DDS_DEFAULT_STATUS_COUNT_DETAIL    (size_t)32
// ==========================================================================

// == Static Assert
#define OMG_DDS_STATIC_ASSERT_DETAIL static_assert

// ==========================================================================

// Logging Macros
#define OMG_DDS_LOG_DETAIL(kind, msg) \
    std::cout << "[" << kind << "]: " << msg << std::endl;

#define DDS_CURRENT_SUBMODULE   DDS_SUBMODULE_MASK_ALL
// ==========================================================================

// Macros identifying C++11 features

#define RTI_CXX11_RVALUE_REFERENCES

// QNX GCC 4.7.3: 
// - std::is_nothrow_move_assignable has strange behavior
// - initializer_list doesn't work
// - and some types in the std library don't specify noexcept
#if !defined(_MSC_VER) \
    && defined(_YVALS) && defined(_CPPLIB_VER) && _CPPLIB_VER <= 650
#define RTI_NO_CXX11_HDR_TYPE_TRAITS
#define RTI_NO_CXX11_HDR_INITIALIZER_LIST
#define RTI_NO_CXX11_NOEXCEPT
#endif

// This macro defines what the default compiler-generated move operations would
// do for a type T that inherits from B and doesn't have any other non-static
// member variables. We need to do this for compilers that support rvalue
// references but don't generate the default move operations (Visual Studio
// 2010-2013).
//
// Note: Per the C++11 standard, the compiler should delete the default
// copy operations when providing a user-defined move ctor or move-assignment.
// But since Visual Studio 2010-2013 doesn't enforce this rule, we don't
// define a copy ctor or copy-assignment operator
//
#define RTI_DEFINE_DEFAULT_MOVE_OPERATIONS(T, B)
#define OMG_DDS_VALUE_TYPE_DEFINE_DEFAULT_MOVE_OPERATIONS(T, D)

#define RTI_FINAL final
#define RTI_OVERRIDE override

#define RTI_EXPLICIT_CONVERSION explicit

#define RTI_NO_CXX17_HDR_OPTIONAL

// Green Hills, VS2013 and gcc4.7.3 do not support thread_local
#if defined(__ghs) || (defined(_MSC_VER) && _MSC_VER <= 1800) \
        || (defined(__GNUC__) && defined(__GNUC_MINOR__)      \
            && !defined(__clang__) && __GNUC__ == 4 && __GNUC_MINOR__ <= 7)
    #define RTI_NO_CXX11_THREAD_LOCAL
    #define RTI_THREAD_LOCAL
#else
    #define RTI_THREAD_LOCAL thread_local
#endif

// Due to a bug in integrity 11.4.4, C++11 features are not supported so we
// need to take them into account to avoid building the rpc feature in
// xmq_cpp.2.0 These features are included by default in integrity 11.7.8,
// so we are only disabling the future in lower version of integrity
#if (defined(__INTEGRITY_MAJOR_VERSION)                                        \
     && (__INTEGRITY_MAJOR_VERSION < 11                                        \
         || (__INTEGRITY_MAJOR_VERSION == 11 && __INTEGRITY_MINOR_VERSION < 7) \
         || (__INTEGRITY_MAJOR_VERSION == 11 && __INTEGRITY_MINOR_VERSION == 7 \
             && __INTEGRITY_PATCH_VERSION < 8)))                               \
        || (defined(_MSC_VER) && _MSC_VER <= 1800)
    #define RTI_NO_CXX11_HDR_FUTURE
#endif

// RPC support requires the following C++11 features: <future>, lambdas, and
// thread_local. We are also disabling this feature in vxWorks due to several
// issues such as: exceptions not propagated correctly in asynchronous mode and
// serveral crashes when using thread_local and large arrays
#if defined(RTI_NO_CXX11_HDR_FUTURE) \
        || defined(RTI_NO_CXX11_THREAD_LOCAL) || defined(__VXWORKS__)
    #define RTI_NO_CXX11_RPC_SUPPORT
#endif


#define RTI_DEFINE_REFTYPE_STD_HASH(ReferenceType__) \
  namespace std { \
  template <> \
  struct hash<ReferenceType__> { \
    size_t operator()(const ReferenceType__& t) const { \
      using delegate_type = typename std::decay<decltype(t.delegate())>::type; \
      return std::hash<delegate_type>()(t.delegate()); \
  } \
  } \
  ; \
  }

#define RTI_DEFINE_TEMPLATE_REFTYPE_STD_HASH(ReferenceType__) \
  namespace std { \
  template <typename T> \
  struct hash<ReferenceType__<T>> { \
    size_t operator()(const ReferenceType__<T>& t) const { \
      using delegate_type = typename std::decay<decltype(t.delegate())>::type; \
      return std::hash<delegate_type>()(t.delegate()); \
  } \
  } \
  ; \
  }

// ==========================================================================



#endif /* OMG_DDS_CORE_MACROS_HPP_*/
