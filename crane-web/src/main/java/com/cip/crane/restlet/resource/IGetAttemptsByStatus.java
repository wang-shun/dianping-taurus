package com.cip.crane.restlet.resource;

import com.cip.crane.restlet.shared.AttemptDTO;
import org.restlet.resource.Get;

import java.util.ArrayList;

/**
 * Created by mkirin on 14-8-12.
 */
public interface IGetAttemptsByStatus {
    @Get
    public ArrayList<AttemptDTO> retrieve();
}
