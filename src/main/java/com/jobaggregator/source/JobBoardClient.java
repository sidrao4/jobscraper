package com.jobaggregator.source;

import java.util.List;

import com.jobaggregator.company.Company;
import com.jobaggregator.company.Source;

public interface JobBoardClient {

    Source source();

    /** Fetches every currently open posting on the company's board. */
    List<NormalizedJob> fetch(Company company);
}
