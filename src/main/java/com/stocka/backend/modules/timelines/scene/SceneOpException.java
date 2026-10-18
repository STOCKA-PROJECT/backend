package com.stocka.backend.modules.timelines.scene;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;

/**
 * An operation could not be applied. Carries the index of the operation in the batch, its name and
 * what went wrong, so the caller (editor or model) can fix exactly that operation.
 */
public class SceneOpException extends ApiException {

    public SceneOpException(int index, String op, String detail) {
        this(index, op, detail, null);
    }

    public SceneOpException(int index, String op, String detail, List<String> candidates) {
        super(HttpStatus.BAD_REQUEST, ErrorCodes.TIMELINE_SCENE_OP_INVALID, params(index, op, detail, candidates));
    }

    private static Map<String, Object> params(int index, String op, String detail, List<String> candidates) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("index", index);
        params.put("op", op);
        params.put("detail", detail);
        if (candidates != null && !candidates.isEmpty()) {
            params.put("candidates", candidates);
        }
        return params;
    }
}
