package com.fis.hackathon.api.web;

import com.fis.hackathon.api.dto.ApiDtos.AppState;
import com.fis.hackathon.api.service.StateQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StateController {

    private final StateQueryService stateQueryService;

    public StateController(StateQueryService stateQueryService) {
        this.stateQueryService = stateQueryService;
    }

    @GetMapping("/api/state")
    public AppState state() {
        return stateQueryService.getState();
    }
}
