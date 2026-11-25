package com.fis.hackathon.payments.web;

import com.fis.hackathon.api.dto.ApiDtos.PaymentView;
import com.fis.hackathon.api.dto.ApiDtos.ResetResult;
import com.fis.hackathon.ledger.service.DemoSeeder;
import com.fis.hackathon.payments.service.PaymentService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoController {

    private final PaymentService paymentService;
    private final DemoSeeder demoSeeder;

    public DemoController(PaymentService paymentService, DemoSeeder demoSeeder) {
        this.paymentService = paymentService;
        this.demoSeeder = demoSeeder;
    }

    @PostMapping("/api/demo/payments/{id}/expire")
    public PaymentView expire(@PathVariable long id) {
        return paymentService.expirePayment(id, true);
    }

    @PostMapping("/api/demo/reset")
    public ResetResult reset() {
        return demoSeeder.reset();
    }
}
