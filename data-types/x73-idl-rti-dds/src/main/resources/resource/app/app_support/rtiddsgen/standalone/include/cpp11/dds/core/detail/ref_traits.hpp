#ifndef OMG_DDS_CORE_DETAIL_REF_TRAITS_H_
#define OMG_DDS_CORE_DETAIL_REF_TRAITS_H_

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

/*
 * This file is non-normative. The implementation is
 * provided only as an example.
 */
#include <memory>
#include <type_traits>

// IMPORTANT: macros.hpp must be the first RTI header included in every header
// file so that symbols are exported correctly on Windows
#include <dds/core/macros.hpp>

#include <dds/core/types.hpp>
#include <dds/core/Exception.hpp>
 
namespace dds { namespace core {

template <typename T>
struct smart_ptr_traits {
    typedef std::shared_ptr<T> ref_type;
    typedef std::weak_ptr<T>   weak_ref_type;
};
 
 
template <typename TO, typename FROM>
TO polymorphic_cast(const FROM& from) {
    typename TO::DELEGATE_REF_T dr =
        std::dynamic_pointer_cast< typename TO::DELEGATE_T>(from.delegate());
    if (!dr) {
        throw InvalidDowncastError("Attempted invalid downcast");
    }
 
    return TO(dr);
}
 
} }


#endif /* OMG_DDS_CORE_DETAIL_REF_TRAITS_H_ */
