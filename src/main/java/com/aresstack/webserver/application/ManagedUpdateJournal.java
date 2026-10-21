package com.aresstack.webserver.application;

import com.aresstack.webserver.domain.Site;

/** Record application replacement intent before modifying a running service. */
public interface ManagedUpdateJournal {
    void prepare(Site previous, Site candidate);
    void commit(Site candidate);
    void rollback(Site candidate);
    void recover();
}
