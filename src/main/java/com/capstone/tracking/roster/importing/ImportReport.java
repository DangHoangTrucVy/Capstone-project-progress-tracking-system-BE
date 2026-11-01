package com.capstone.tracking.roster.importing;

import java.util.List;
import java.util.UUID;

/**
 * The check screen shown before an import is written, and the result once confirmed (4.1): new / updated / rejected
 * rows, each rejected row with its reason. {@code committed} false: nothing was written (preview).
 *
 * @param newMajors       majors the file names that do not exist yet (created on confirm)
 * @param blockedFinalized legacy compatibility field; always empty under the revised rules (updates work after finalization)
 */
public record ImportReport(
        boolean committed,
        UUID batchId,
        int newRows,
        int updatedRows,
        int unchangedRows,
        int errorRows,
        List<RowError> errors,
        List<String> newMajors,
        List<RowError> blockedFinalized
) {
    public record RowError(int row, String key, String reason) {}
}
