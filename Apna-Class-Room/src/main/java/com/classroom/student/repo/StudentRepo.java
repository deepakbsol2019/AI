package com.classroom.student.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.classroom.student.entity.Student;

@Repository
public interface StudentRepo extends JpaRepository<Student, Long> {

}
