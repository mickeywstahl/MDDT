/*
 Copyright (c) 2013, Real-Time Innovations, Inc. All rights reserved.

 No duplications, whole or partial, manual or electronic, may be made
 without express written permission.  Any such copies, or
 revisions thereof, must display this notice unaltered.
 This code contains trade secrets of Real-Time Innovations, Inc.
*/

#ifndef RTI_DDS_CORE_OPTIONAL_HPP_
#define RTI_DDS_CORE_OPTIONAL_HPP_

// IMPORTANT: macros.hpp must be the first RTI header included in every header
// file so that symbols are exported correctly on Windows
#include <dds/core/macros.hpp>

// DllExport.hpp should be the second include
#include <rti/core/DllExport.hpp>
#include <dds/core/Exception.hpp>
#include <dds/core/String.hpp> // for optional specialization for strings
#include <rti/core/memory.hpp>

/** @RTI_namespace_start dds::core */
namespace rti { namespace core {

/**
 * @ingroup DDSCPP2SupportingTypes
 * @headerfile dds/core/Optional.hpp
 *
 * @brief @st_value_type Represents an object that may not contain a valid value
 *
 * @tparam T The type of the actual object this \p optional<T> wraps
 *
 * Members of an \ref DDSTypesModule "IDL" type marked with the \p \@optional tag
 * map to this C++ type.
 *
 * When an optional value has a valid value has_value() returns true and `operator*` returns
 * a reference to the actual object of type T. Otherwise has_value() returns false
 * and `operator*` throws dds::core::PreconditionNotMetError. To assing a value you
 * can use the assignment operator.
 *
 * An optional object has full \ref a_st_value_type "value-type semantics";
 * copying an optional value copies the underlying object if it exists.
 *
 * This type's API is similar to that of `std::optional`.
 *
 * @see DDSTypesModule
 */
template <typename T>
class UserDllExport optional {
public:
    typedef T value_type;
    typedef memory::ObjectAllocator<T> Allocator;

    /**
     * @brief Create an unset optional object
     *
     * @post !has_value()
     */
    optional() OMG_NOEXCEPT
        : value_ (NULL)
    {
    }

    /**
     * @brief Create an optional object with a copy of a value
     *
     * @post `has_value() && *(*this) == value`
     */
    optional(const T& value)
        : value_ (Allocator::create(value))
    {
    }

  #ifdef RTI_CXX11_RVALUE_REFERENCES
    /**
     * @brief @st_cpp11 Create an optional object moving a value
     *
     * @post has_value()
     */
    optional(T&& value)
        : value_(Allocator::create(std::forward<T>(value)))
    {
    }
  #endif

    /**
     * @brief Create an optional member conditionally set or unset.
     *
     * @param condition If true creates an optional member with \p value otherwise
     * it creates an unset optional member
     * @param value The value to set if condition is true
     *
     * @post has_value() == condition
     */
    optional(bool condition, const T& value)
        : value_ (NULL)
    {
        if (condition) {
            value_ = Allocator::create(value);
        }
    }

  #ifdef RTI_CXX11_RVALUE_REFERENCES
    /**
     * @brief @st_cpp11 Creates an optional member conditionally set or unset
     * by moving a value.
     *
     * Moves the value rather than copying it.
     *
     * @see optional(bool, const T&)
     */
    optional(bool condition, T&& value)
        : value_ (NULL)
    {
        if (condition) {
            value_ = Allocator::create(std::forward<T>(value));
        }
    }
  #endif

    /*
     * @brief Copy constructor
     *
     * If \p other is set it copies the underlying object.
     */
    optional(const optional<T>& other)
        : value_ (NULL)
    {
        if (other.has_value()) {
            value_ = Allocator::create(other.get());
        }
    }

  #ifdef RTI_CXX11_RVALUE_REFERENCES
    /*
     * @brief @st_cpp11 Move constructor
     *
     * If \p other is set it moves the underlying object.
     */
    optional(optional<T>&& other) OMG_NOEXCEPT
        : value_(other.value_)
    {
        other.value_ = NULL;
    }
  #endif

    /**
     * @brief Destroys the underlying object if it exists
     */
    ~optional()
    {
        reset();
    }

    /**
     * @brief Assigns a copy of an object
     *
     * @deprecated Use `operator=` instead.
     */
    void set(const T& value)
    {
        if (value_ == NULL) {
            value_ = Allocator::create(value);
        } else {
            *value_ = value;
        }
    }

  #ifdef RTI_CXX11_RVALUE_REFERENCES
    /**
     * @brief @st_cpp11 Assigns an object by moving it
     *
     * @deprecated Use `operator=` instead.
     */
    void set(T&& value)
    {
        if (value_ == NULL) {
            // Create by using T's move ctor if available
            value_ = Allocator::create(std::forward<T>(value));
        } else {
            // Use T's move assignment operator if available
            *value_ = std::move(value);
        }
    }
  #endif


    /**
     * @brief Checks if this optional instance contains a valid object
     *
     * @deprecated Use \p has_value()
     */
    bool is_set() const OMG_NOEXCEPT
    {
        return value_ != NULL;
    }

    /**
     * @brief Checks if this optional instance contains a valid object
     *
     * If `my_optional.has_value()` is `true`, then `*my_optional` returns a
     * valid object
     */
    bool has_value() const OMG_NOEXCEPT
    {
        return value_ != NULL;
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
        return has_value();
    }
#endif

    /**
     * @brief Destroys the underlying object and leaves this optional with
     * an invalid (unset) value
     *
     * @post !has_value()
     */
    void reset()
    {
        if (value_ != NULL) {
            Allocator::destroy(value_);
            value_ = NULL;
        }
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

        return *value_;
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

        return *value_;
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
     * @brief Returns &get() if the object is initialized or NULL otherwise.
     *
     * @deprecated
     */
    const T* get_ptr() const
    {
        return value_;
    }

    /**
     * @brief Returns &get() if the object is initialized or NULL otherwise.
     *
     * @deprecated
     */
    T* get_ptr()
    {
        return value_;
    }

    /**
     *  Get the value, without checking if it exists
     *
     *  @pre has_value(), otherwise this operation has undefined behavior. See
     *  value().
     */
    const T& operator*() const
    {
        return *value_;
    }

    /**
     *  Get the value, without checking if it exists
     *
     *  @pre has_value(), otherwise this operation has undefined behavior. See
     *  value().
     */
    T& operator*()
    {
        return *value_;
    }

    /**
     *  Get the value.
     *
     *  @throws dds::core::PreconditionNotMetError if !has_value()
     */
    const T* operator->() const
    {
        return &get();
    }

    /**
     *  Get the value.
     *
     *  @throws dds::core::PreconditionNotMetError if !has_value()
     */
    T* operator->()
    {
        return &get();
    }

    /**
     * @brief Assignment operator
     *
     * Copies `*other` if it exists.
     */
    optional<T>& operator= (const optional<T>& other)
    {
        if (other.has_value()) {
            set(*other);
        } else {
            reset();
        }

        return *this;
    }

  #ifdef RTI_CXX11_RVALUE_REFERENCES
    /**
     * @brief @st_cpp11 Move-assignment operator
     *
     * Moves `*other` if it exists.
     */
    optional<T>& operator= (optional<T>&& other) OMG_NOEXCEPT
    {
        std::swap(value_, other.value_);
        return *this;
    }
  #endif

    /**
     * @brief Assign a (valid) value
     *
     * @param value The value to assign to this optional member
     *
     * @post `*(*this) == value`
     */
    optional<T>& operator= (const T& value)
    {
        set(value);
        return *this;
    }

  #ifdef RTI_CXX11_RVALUE_REFERENCES
    /**
     * @brief Assign a (valid) value by moving an object
     *
     * @param value The value to move into this optional member
     *
     * @post has_value()
     */
    optional<T>& operator= (T&& value)
    {
        set(std::forward<T>(value)); // call move overload of set()
        return *this;
    }
  #endif

    /**
     * @brief Swaps the underlying objects
     *
     * This operation is always O(1).
     */
    friend void swap(optional<T>& left, optional<T>& right) OMG_NOEXCEPT
    {
        std::swap(left.value_, right.value_); // swap the underlying pointer
    }

private:
    T * value_;
};

// --- Specialization for dds::core strings
// (deprecated -- only used in legacy code generation)
//
template <typename CharType>
class UserDllExport optional<
    typename dds::core::basic_string<
        CharType,
        typename memory::OsapiAllocator<CharType> > > {
public:
    typedef typename memory::OsapiAllocator<CharType> Allocator;
    typedef typename dds::core::basic_string<CharType, Allocator> StringType;
    typedef StringType value_type;
    typedef typename StringType::create_null_tag_t create_null_tag_t;

    /**
     * Initialize an unset optional member.
     *
     * @post !has_value()
     */
    optional() OMG_NOEXCEPT
        : value_ (create_null_tag_t())
    {
    }

    /**
     * Initialize an optional member with a value (makes a copy)
     *
     */
    optional(const StringType& value)
        : value_ (value)
    {
    }

  #ifdef RTI_CXX11_RVALUE_REFERENCES
    optional(StringType&& value)
        : value_(std::forward<StringType>(value))
    {
    }
  #endif

    /**
     * Initialize an optional member conditionally. If the condition is true
     * it initializes it with a copy of value; otherwise it is not set
     */
    optional(bool condition, const StringType& value)
        : value_ (create_null_tag_t())
    {
        if (condition) {
            value_ = value;
        }
    }

  #ifdef RTI_CXX11_RVALUE_REFERENCES
    optional(bool condition, StringType&& value)
        : value_ (create_null_tag_t())
    {
        if (condition) {
            value_ = std::forward<StringType>(value);
        }
    }
  #endif

    /*
     * Initializes an optional by copying the content of another optional
     * member if it is set
     */
    optional(const optional<StringType>& other)
        : value_ (create_null_tag_t())
    {
        if (other.has_value()) {
            value_ = other.value_;
        }
    }

  #ifdef RTI_CXX11_RVALUE_REFERENCES
    optional(optional<StringType>&& other) OMG_NOEXCEPT
        : value_(std::move(other.value_))
    {
    }
  #endif

    ~optional()
    {
    }

    void set(const StringType& value)
    {
        value_ = value;
    }

  #ifdef RTI_CXX11_RVALUE_REFERENCES
    void set(StringType&& value)
    {
        value_ = std::move(value);
    }
  #endif

    bool has_value() const
    {
        return value_.c_str() != NULL;
    }

#if !defined(RTI_NO_CXX11_EXPLICIT_CONVERSION_OPERATORS)
    explicit operator bool() const OMG_NOEXCEPT
    {
        return has_value();
    }
#endif

    bool is_set() const
    {
        return has_value();
    }

    /**
     * Reset the value.
     *
     * @post !has_value()
     */
    void reset()
    {
        if (has_value()) {
            Allocator::release(value_.native());
            value_.native() = NULL;
        }
    }

    /**
     *  Get the value. An exception is thrown if the value is not set.
     */
    const StringType& get() const
    {
        return value();
    }

    /**
     *  Get the value. An exception is thrown if the value is not set.
     */
    StringType& get()
    {
        return value();
    }

    const StringType& value() const
    {
        if (!has_value()) {
            throw dds::core::PreconditionNotMetError(
                "uninitialized optional value");
        }

        return value_;
    }

    /**
     *  Get the value. An exception is thrown if the value is not set.
     */
    StringType& value()
    {
        if (!has_value()) {
            throw dds::core::PreconditionNotMetError(
                "uninitialized optional value");
        }

        return value_;
    }

    /**
     * @brief Returns &get() if the object is initialized or NULL otherwise.
     */
    const StringType* get_ptr() const
    {
        if (has_value()) {
            return &value_;
        } else {
            return NULL;
        }
    }

    /**
     * @brief Returns &get() if the object is initialized or NULL otherwise.
     */
    StringType* get_ptr()
    {
        if (has_value()) {
            return &value_;
        } else {
            return NULL;
        }
    }

    optional<StringType>& operator= (const optional<StringType>& other)
    {
        if (other.has_value()) {
            set(other.get());
        } else {
            reset();
        }

        return *this;
    }

  #ifdef RTI_CXX11_RVALUE_REFERENCES
    optional<StringType>& operator= (optional<StringType>&& other) OMG_NOEXCEPT
    {
        swap(value_, other.value_);
        return *this;
    }
  #endif

    optional<StringType>& operator= (const StringType& value)
    {
        set(value);
        return *this;
    }

  #ifdef RTI_CXX11_RVALUE_REFERENCES
    optional<StringType>& operator= (StringType&& value)
    {
        set(std::forward<StringType>(value)); // call move overload of set()
        return *this;
    }
  #endif

    friend void swap(optional<StringType>& left, optional<StringType>& right) OMG_NOEXCEPT
    {
        swap(left.value_, right.value_); // swap the underlying pointer
    }

private:
    StringType value_;
};

// comparison operators:

/**
 * @relatesalso dds::core::optional
 *
 * @brief Compares two optional values
 *
 * @return true if both are unset or both are set and `*a == *b`.
 *
 */
template <typename T>
bool operator ==(const optional<T>& a, const optional<T>& b)
{
    if (a.has_value() != b.has_value()) {
        return false; // one is set and one isn't
    }

    if (!a.has_value()) {
        return true; // none is set
    }

    return a.get() == b.get(); // both are set
}

/**
 * @relatesalso dds::core::optional
 *
 * @brief Compares two optional values
 *
 * @return false if both are unset or both are set and `*a == *b`.
 *
 */
template <typename T>
bool operator !=(const optional<T>& a, const optional<T>& b)
{
    return !(a == b);
}

/**
 * @relatesalso dds::core::optional
 *
 * @brief Compares an optional member and a value of the underlying type
 *
 * @return Return true if `optional_value` is set and `*optional_value == value`
 */
template <typename T>
bool operator ==(const optional<T>& optional_value, const T& value)
{
    if (!optional_value.has_value()) {
        return false;
    }

    return optional_value.get() == value;
}

/**
 * @relatesalso dds::core::optional
 *
 * @brief Compares an optional member and a value of the underlying type
 *
 * @return Return true if optional_value is set and `*optional_value == value`
 */
template <typename T>
bool operator ==(const T& value, const optional<T>& optional_value)
{
    return optional_value == value;
}

/**
 * @relatesalso dds::core::optional
 *
 * @brief Compares an optional member and a value of the underlying type
 *
 * @return Return false if optional_value is set and `*optional_value == value`
 */
template <typename T>
bool operator !=(const optional<T>& optional_value, const T& value)
{
    return !(optional_value == value);
}

/**
 * @relatesalso dds::core::optional
 *
 * @brief Compares an optional member and a value of the underlying type
 *
 * @return Return false if optional_value is set and `*optional_value == value`
 */
template <typename T>
bool operator !=(const T& value, const optional<T>& optional_value)
{
    return !(optional_value == value);
}

/**
 * @relatesalso dds::core::optional
 *
 * @brief Applies `operator<<` to `*optional` or to the string \p "NULL" if
 * `!optional.has_value()`.
 */
template<typename T>
std::ostream& operator <<(std::ostream& out, const optional<T>& optional)
{
    if (optional.has_value()) {
        out << optional.get();
    } else {
        out << "null";
    }
    return out;
}

} }
#endif /* RTI_DDS_CORE_OPTIONAL_HPP_ */
