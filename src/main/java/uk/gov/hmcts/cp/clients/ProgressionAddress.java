package uk.gov.hmcts.cp.clients;

import lombok.Getter;
import lombok.Setter;

/** Defendant address as returned by Progression's group-cases query (personal data). */
@Getter
@Setter
public class ProgressionAddress {

    private String address1;
    private String address2;
    private String address3;
    private String address4;
    private String address5;
    private String postcode;
}
