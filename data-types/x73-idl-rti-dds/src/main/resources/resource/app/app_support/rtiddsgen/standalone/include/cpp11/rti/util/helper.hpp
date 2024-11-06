/* $Id$

(c) Copyright, Real-Time Innovations, 2014-2016.
All rights reserved.

No duplications, whole or partial, manual or electronic, may be made
without express written permission.  Any such copies, or
revisions thereof, must display this notice unaltered.
This code contains trade secrets of Real-Time Innovations, Inc.
*/

#ifndef RTI_UTIL_HELPER_HPP_
#define RTI_UTIL_HELPER_HPP_

// IMPORTANT: macros.hpp must be the first RTI header included in every header
// file so that symbols are exported correctly on Windows
#include <dds/core/macros.hpp>
#include <stdexcept>
#include <limits>
#include <type_traits>

#include <dds/core/Exception.hpp>
#include <rti/core/OptionalValue.hpp>

namespace rti { namespace util {

namespace detail {

template <bool CheckOverflow>
struct size_cast_helper {

    template <typename IntType>
    static IntType cast(size_t value)
    {
        // The extra parenthesis work around the Windows max() macro
        if (value > static_cast<size_t>((std::numeric_limits<IntType>::max)())) {
            throw std::out_of_range(
                "possible overflow in cast from size_t");
        }

        return static_cast<IntType>(value);
    }
};

template <>
struct size_cast_helper<false> {
    template <typename IntType>
    static IntType cast(size_t value)
    {
        return static_cast<IntType>(value);
    }
};

// Determines if an integer type need an overflow check when
// cast from size_t: when sizeof(size_t) is greater or when
// they are the same but IntType is signed.
template<typename IntType>
struct needs_overflow_check_from_size_t
        : std::integral_constant<
                  bool,
                  (sizeof(size_t) > sizeof(IntType)
                   || (sizeof(size_t) == sizeof(IntType)
                       && std::is_signed<IntType>::value))> {
};

} // detail

template <typename IntType>
IntType size_cast(size_t length)
{
    static_assert(
            std::is_integral<IntType>::value,
            "IntType must be an integer type");

    // Overflow check when the size of the int is not the same as size_t or when
    // the type is signed.
    return detail::size_cast_helper<detail::needs_overflow_check_from_size_t<
            IntType>::value>::template cast<IntType>(length);
}

template <typename T>
bool equal_ptr(const T * a, const T * b);

template <typename T>
std::ostream& print_ptr(std::ostream& out, const T * ptr);

OMG_DDS_API
std::string ptr_to_str(const void * p);

OMG_DDS_API
void * str_to_ptr(const std::string& s);

template <typename T>
bool equal_ptr(const T * a, const T * b)
{
    if (a == b) {
        return true; // Both are NULL or same address
    }

    if (a == NULL || b == NULL) {
        return false; // One is NULL and one isn't
    }

    return *a == *b; // None is NULL
}

template <typename T>
std::ostream& print_ptr(std::ostream& out, const T * ptr)
{
    if (ptr == NULL) {
        out << "NULL";
    } else {
        out << *ptr;
    }

    return out;
}

/*
 * @brief Replaces a native string with a new value.
 *
 * @param native_str A pointer to the native string to be replaced.
 * @param optional_str An optional value containing the new string value.
 *
 * @throws dds::core::PreconditionNotMetError if native_str is NULL.
 * @throws std::bad_alloc if memory allocation fails.
 */
inline void native_string_replace(
        char **native_str,
        const rti::core::optional_value<std::string>& optional_str)
{
    if (native_str == NULL) {
        throw dds::core::PreconditionNotMetError(
                "native_str cannot be NULL");
    }

    if (optional_str.has_value()) {
        if (!DDS_String_replace(native_str, optional_str->c_str())) {
            throw std::bad_alloc();
        }
    } else {
        DDS_String_free(*native_str);
        *native_str = NULL;
    }
}

} } // namespace rti::util

#endif // RTI_UTIL_HELPER_HPP_
