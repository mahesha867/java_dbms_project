package com.attendance;

import com.attendance.util.PasswordUtil;

public class GenerateBcrypt {
    public static void main(String[] args) {
        System.out.println("teacher123:" + PasswordUtil.hashPassword("teacher123"));
        System.out.println("student123:" + PasswordUtil.hashPassword("student123"));
    }
}
