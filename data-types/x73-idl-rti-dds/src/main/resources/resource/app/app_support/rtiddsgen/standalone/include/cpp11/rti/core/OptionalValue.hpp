/*
 Copyright (c) 2013, Real-Time Innovations, Inc. All rights reserved.

 No duplications, whole or partial, manual or electronic, may be made
 without express written permission.  Any such copies, or
 revisions thereof, must display this notice unaltered.
 This code contains trade secrets of Real-Time Innovations, Inc.
*/

#ifndef RTI_DDS_CORE_OPTIONAL_VALUE_HPP_
#define RTI_DDS_CORE_OPTIONAL_VALUE_HPP_

// IMPORTANT: macros.hpp must be the first RTI header included in every header
// file so that symbols are exported correctly on Windows
#include <dds/core/macros.hpp>

#if !defined(RTI_NO_CXX17_HDR_OPTIONAL) && defined(RTI_USE_STD_OPTIONAL)
#include <optional>
#endif

#include <dds/core/Exception.hpp>

namespace rti { namespace core {

// The API can be compiled with std::optional instead of rti::core::optional_value
//
// Both the libraries and the user application need to use the flag
// -DRTI_USE_STD_OPTIONAL
//
// This option is currently not exercised on any platform. See CORE-8567.
//
#if !defined(RTI_NO_CXX17_HDR_OPTIONAL) && defined(RTI_USE_STD_OPTIONAL)

template <typename T>
using optional_value = std::optional<T>;

#else

// This is a very basic implementation--boost::optional more efficient and flexible.
// Constructors of dds::core::optional<T> always call the default constructor
// of T. rtiboost::optional by-passes the default ctor by using a memory buffer
//  where it constructs T in-place only as needed.
//
// This class differs from dds::core::optional<T> in that optional_value<T> keeps
// a value of the type (T value_) whereas optional<T> keeps a pointer (T*). We
// use optional_value in some RTI-extension APIs; optional<T> is for XTypes
// optional members in topic types.
//
//

/**
 * @ingroup DDSCPP2SupportingTypes
 * @brief @extension Represents a value that can be initialized or not
 *
 * This class is similar to dds::core::optional and `std::optional`.
 *
 * They have different implementations. dds::core::optional is only used in
 * IDL-generated types, while rti::core::optional_value is used in parts of the 
 * API.
 */
template <typename T>
class optional_value {

public:
    /**
     * @brief Creates an uninitialized value
     */
    optional_value() : is_set_(false) { }

    /**
     * @brief Creates an instance with a value
     */
    optional_value(const T& value) : value_(value), is_set_(true) { }

    /**
     * @brief Conditionally creates an instance that can be uninitialized or
     * initialized with a value.
     *
     * @param condition If it is true it assigns \p value, otherwise this
     * optional_value is uninitialized
     * @param value The value to use if \p condition is true.
     */
    optional_value(bool condition, const T& value) : is_set_(condition)
    {
        if (condition) {
            value_ = value;
        }
    }

    /**
     * @brief Copy constructor
     *
     * This optional_value will be initialized only if \p other is initialized.
     */
    optional_value(const optional_value<T>& other) : is_set_(other.is_set_)
    {
        if (other.has_value()) {
            value_ = other.get();
        }
    }

    #if defined(RTI_CXX11_RVALUE_REFERENCES) \
            && !defined(RTI_CXX11_NO_IMPLICIT_MOVE_OPERATIONS)
    optional_value(optional_value&&) = default;
    optional_value& operator=(optional_value&&) = default;
    #endif

    optional_value& operator=(const optional_value<T>& other)
    {
        if (other.has_value()) {
            value_ = *other;
            is_set_ = true;
        } else {
            reset();
        }

        return *this;
    }

    /**
     * @brief Returns true only if the value is initialized.
     *
     * @deprecated Use \p has_value()
     */
    bool is_set() const OMG_NOEXCEPT
    {
        return is_set_;
    }

    /**
     * @brief Returns true only if the value is initialized.
     */
    bool has_value() const OMG_NOEXCEPT
    {
        return is_set_;
    }

    // Operator bool only defined if the compiler supports explicit conversion
    // operators. Otherwise, an implicit conversion to bool creates too many
    // possibilities for bugs.
  #if !defined(RTI_NO_CXX11_EXPLICIT_CONVERSION_OPERATORS)
    /**
     * @brief @st_cpp11 Returns has_value()
     */
    explicit operator bool() const OMG_NOEXCEPT
    {
        return is_set_;
    }
  #endif

    /**
     * @brief After calling this function, this optional_value is not set.
     */
    void reset()
    {
        is_set_ = false;
    }

    /**
     * @brief Retrieves the underlying object if it exists
     *
     * This operation, unlike `operator*` throws an exception if the underlying
     * object doesn't exist.
     *
     * @throws dds::core::PreconditionNotMetError if !has_value().
     */
    const T& value() const
    {
        if (!has_value()) {
            throw dds::core::PreconditionNotMetError(
                "uninitialized optional value");
        }

        return value_;
    }

    /**
     * @brief Retrieves the underlying object if it exists
     *
     * This operation, unlike `operator*` throws an exception if the underlying
     * object doesn't exist.
     *
     * @throws dds::core::PreconditionNotMetError if !has_value().
     */
    T& value()
    {
        if (!has_value()) {
            throw dds::core::PreconditionNotMetError(
                "uninitialized optional value");
        }

        return value_;
    }

    /**
     * @brief Retrieves the underlying object if it exists
     *
     * @deprecated Use value() or `operator*`
     *
     * @throws dds::core::PreconditionNotMetError if !has_value().
     */
    const T& get() const
    {
        return value();
    }

    /**
     * @brief Retrieves the underlying object if it exists
     *
     * @deprecated Use value() or `operator*`
     *
     * @throws dds::core::PreconditionNotMetError if !has_value().
     */
    T& get()
    {
        return value();
    }

    /**
     *  Get the value, without checking if it exists
     *
     *  @pre has_value(), otherwise this operation has undefined behavior. See
     *  value().
     */
    const T& operator*() const
    {
        return value_;
    }

    /**
     *  Get the value, without checking if it exists
     *
     *  @pre has_value(), otherwise this operation has undefined behavior. See
     *  value().
     */
    T& operator*()
    {
        return value_;
    }

    /**
     *  Get the value.
     *
     *  @throws dds::core::PreconditionNotMetError if !has_value()
     */
    const T* operator->() const
    {
        return &value();
    }

    /**
     *  Get the value.
     *
     *  @throws dds::core::PreconditionNotMetError if !has_value()
     */
    T* operator->()
    {
        return &value();
    }

    /**
     * @brief Swaps the underlying objects
     * 
     * It uses `swap(*left, *right)` if that function
     * is defined for type `T`; otherwise it uses `std::swap`.
     */
    friend void swap(
        optional_value<T>& left,
        optional_value<T>& right) OMG_NOEXCEPT
    {
        using std::swap;

        if (left.is_set_ || right.is_set_) {
            swap(left.value_, right.value_);
            swap(left.is_set_, right.is_set_);
        }
    }

private:
    T value_;
    bool is_set_;
};

template <typename T>
bool operator==(const optional_value<T>& left, const optional_value<T>& right)
{
    if (left.has_value()) {
        if (right.has_value()) {
            return *left == *right;
        } else {
            return false;
        }
    } else {
        return !right.has_value();
    }
}

template <typename T>
bool operator!=(const optional_value<T>& left, const optional_value<T>& right)
{
    return !(left == right);
}

#endif  // !defined(RTI_NO_CXX17_HDR_OPTIONAL) && defined(RTI_USE_STD_OPTIONAL)
} }
#endif /* RTI_DDS_CORE_OPTIONAL_VALUE_HPP_ */
