package com.cip.crane.restlet.resource;

import org.restlet.resource.Get;

/**
 * Created by kirinli on 15/2/2.
 */
public interface IHostTaskExecTime {
    @Get
    public String retrieve();
}
