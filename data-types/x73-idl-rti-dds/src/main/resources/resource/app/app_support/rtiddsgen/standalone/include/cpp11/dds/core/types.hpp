#ifndef OMG_DDS_CORE_TYPES_HPP_
#define OMG_DDS_CORE_TYPES_HPP_

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

// ISO C++ Includes
#include <string>
#include <vector>
#ifndef RTI_NO_CXX11_NULLPTR
#include <cstddef>
#endif

// DDS Includes

// IMPORTANT: macros.hpp must be the first RTI header included in every header
// file so that symbols are exported correctly on Windows
#include <dds/core/macros.hpp>

#include <dds/core/detail/inttypes.hpp>
#include <dds/core/detail/conformance.hpp>

#include <type_traits>

/**
 * @defgroup DDSCPP2SupportingTypes Supporting Types and Constants
 * @ingroup DDSInfrastructureModule
 *
 * @brief Miscellaneous, general-purpose types and constants
 */

namespace dds { namespace core {

/**
 * @ingroup DDSCPP2SupportingTypes
 * @brief A vector of bytes
 */
typedef std::vector<uint8_t> ByteSeq;

/**
 * @ingroup DDSCPP2SupportingTypes
 * @brief A vector of strings
 */
typedef std::vector<std::string> StringSeq;

// DDS Null-Reference
#ifdef RTI_NO_CXX11_NULLPTR
/**
 * @ingroup DDSCPP2SupportingTypes
 * @brief The type of dds::core::null
 */
class null_type {
};
#else

/**
 * @ingroup DDSCPP2SupportingTypes
 * @brief @st_cpp11 The type of dds::core::null
 */
typedef std::nullptr_t null_type;
#endif

/**
 * @ingroup DDSCPP2SupportingTypes
 * @brief Indicates an empty reference
 * @see @st_ref_type
 */
extern OMG_DDS_API_VARIABLE const null_type null;

/** @RTI_remove_lines 1 */
#ifdef OMG_DDS_EXTENSIBLE_AND_DYNAMIC_TOPIC_TYPE_SUPPORT    
  namespace policy {

    /**
     * @ingroup DDSDataRepresentationQosModule
     * @brief The type of the elements that DataRepresentation contains
     *
     * 2-byte signed integers
     */
    typedef int16_t DataRepresentationId;

    /**
     * @ingroup DDSDataRepresentationQosModule
     * @brief A vector of DataRepresentationId
     */
    typedef std::vector<DataRepresentationId> DataRepresentationIdSeq;
  }
/** @RTI_remove_lines 1 */
#endif

namespace policy {
/**
 * @ingroup DDSQosTypesModule
 * @brief Identifies a QoS policy
 *
 * The ID for a given policy can be obtained with dds::core::policy::policy_id.
 *
 * For example, the policy ID for the dds::core::policy::Deadline policy is
 * \p dds::core::policy::policy_id<dds::core::policy::Deadline>::value.
 */
typedef uint32_t QosPolicyId;
}

/**
 * @ingroup DDSCPP2SupportingTypes
 * @brief True type used by trait types
 */
using ::std::true_type;
/**
 * @ingroup DDSCPP2SupportingTypes
 * @brief False type used by trait types
 */
using ::std::false_type;

} } // namespace rti::core

#endif /* OMG_DDS_CORE_TYPES_HPP_ */
