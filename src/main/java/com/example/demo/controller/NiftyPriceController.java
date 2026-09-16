package com.example.demo.controller;

import com.example.demo.dto.NiftySpotPrice;
import com.example.demo.service.NiftySpotPriceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NiftyPriceController {

    private final NiftySpotPriceService niftySpotPriceService;

    public NiftyPriceController(NiftySpotPriceService niftySpotPriceService) {
        this.niftySpotPriceService = niftySpotPriceService;
    }

    @GetMapping("/api/nifty/spot")
    public NiftySpotPrice getNiftySpotPrice() {
        return niftySpotPriceService.getNiftySpotPrice();
    }
}

