package com.attendance.dao;

import java.util.List;
import java.util.Optional;

/**
 * Generic Data Access Object (DAO) interface.
 * Demonstrates:
 * - Interface (OOP abstraction contract)
 * - Polymorphism with Generics (type-safe data operations)
 *
 * @param <T> The model entity type
 */
public interface Dao<T> {

    Optional<T> findById(int id);

    List<T> findAll();

    boolean save(T entity);

    boolean update(T entity);

    boolean delete(int id);
}
