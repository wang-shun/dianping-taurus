package com.cip.crane.restlet.resource;

import org.restlet.resource.Get;

/**
 * Created by kirinli on 14-9-30.
 */
public interface IFailedTaskLoad {
    @Get
    public String retrieve();
}
