package com.akatsuki.kerenhr.dto;

import jakarta.validation.constraints.NotNull;

public record McpToggleRequest(
    @NotNull Boolean enabled
) {
}
