#ifndef OMG_TYPES_OPTIONAL_HPP_
#define OMG_TYPES_OPTIONAL_HPP_

/* Copyright 2023, Object Management Group, Inc.
 * Copyright 2023, Real-Time Innovations, Inc.
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

// IMPORTANT: macros.hpp must be the first RTI header included in every header
// file so that symbols are exported correctly on Windows
#include <dds/core/macros.hpp>

// DllExport.hpp should be the second include
#include <rti/core/DllExport.hpp>
#include <dds/core/Optional.hpp>

namespace omg { namespace types {

/**
 * @ingroup DDSCPP2SupportingTypes
 *
 * @brief Optional type according to the IDL4-C++ OMG specification. Alias of dds::core::optional
 */
template <typename T>
using optional = dds::core::optional<T>;

} } // namespace omg::types

#endif // OMG_TYPES_OPTIONAL_HPP_
