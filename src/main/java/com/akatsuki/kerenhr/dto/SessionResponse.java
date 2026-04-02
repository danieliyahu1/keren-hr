package com.akatsuki.kerenhr.dto;

public record SessionResponse(
    String id,
    String title,
    long createdAt,
    long updatedAt,
    boolean active
) {
}
