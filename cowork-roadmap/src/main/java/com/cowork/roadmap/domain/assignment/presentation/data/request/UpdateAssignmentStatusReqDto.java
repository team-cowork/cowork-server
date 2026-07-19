package com.cowork.roadmap.domain.assignment.presentation.data.request;

import com.cowork.roadmap.domain.assignment.entity.AssignmentStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateAssignmentStatusReqDto(

        @Schema(description = "진행 상태", example = "IN_PROGRESS") @NotNull AssignmentStatus status) {
}
