package com.example.gewerbeamt.receive;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only view of what the poller has received so far, so you can watch
 * submissions arrive after sending one.
 *
 * <pre>
 * curl -sS localhost:8080/api/empfangene-gewerbeanmeldungen | jq
 * </pre>
 */
@RestController
@RequestMapping("/api/empfangene-gewerbeanmeldungen")
public class ReceivedGewerbeanmeldungController {

    private final ReceivedAntragStore store;

    public ReceivedGewerbeanmeldungController(ReceivedAntragStore store) {
        this.store = store;
    }

    @GetMapping
    public List<ReceivedGewerbeanmeldung> list() {
        return store.all();
    }
}
