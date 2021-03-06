/*******************************************************************************
 * Copyright (c) 2010, 2015 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Stuart McCulloch (Sonatype, Inc.) - initial API and implementation
 *******************************************************************************/
package org.eclipse.sisu.bean;

import java.util.ArrayList;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.matcher.Matcher;
import com.google.inject.matcher.Matchers;

/**
 * Schedules safe activation of beans even when cyclic dependencies are involved.<br>
 * Takes advantage of the new Guice ProvisionListener SPI, if available at runtime.
 */
public abstract class BeanScheduler
{
    // ----------------------------------------------------------------------
    // Static initialization
    // ----------------------------------------------------------------------

    static
    {
        Object activator;
        try
        {
            // extra check in case we have both old and new versions of guice overlapping on the runtime classpath
            Binder.class.getMethod( "bindListener", Matcher.class, com.google.inject.spi.ProvisionListener[].class );

            activator = new Activator();
        }
        catch ( final Exception e )
        {
            activator = null;
        }
        catch ( final LinkageError e )
        {
            activator = null;
        }
        ACTIVATOR = activator;
    }

    // ----------------------------------------------------------------------
    // Constants
    // ----------------------------------------------------------------------

    static final Object ACTIVATOR;

    static final Object PLACEHOLDER = new Object();

    public static final Module MODULE = new Module()
    {
        public void configure( final Binder binder )
        {
            if ( null != ACTIVATOR )
            {
                binder.bindListener( Matchers.any(), (com.google.inject.spi.ProvisionListener) ACTIVATOR );
            }
        }
    };

    // ----------------------------------------------------------------------
    // Implementation fields
    // ----------------------------------------------------------------------

    private static final ThreadLocal<Object[]> pendingHolder = new ThreadLocal<Object[]>();

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    /**
     * Schedules activation of the given bean at the next safe activation point.
     * 
     * @param bean The managed bean
     */
    public final void schedule( final Object bean )
    {
        if ( null != ACTIVATOR )
        {
            final Object[] holder = getPendingHolder();
            final Object pending = holder[0];
            if ( pending == PLACEHOLDER )
            {
                holder[0] = new Pending( bean );
                return; // will be activated later
            }
            else if ( pending instanceof Pending )
            {
                ( (Pending) pending ).add( bean );
                return; // will be activated later
            }
        }
        activate( bean ); // no ProvisionListener, so activate immediately
    }

    // ----------------------------------------------------------------------
    // Customizable methods
    // ----------------------------------------------------------------------

    /**
     * Customized activation of the given bean.
     * 
     * @param bean The bean to activate
     */
    protected abstract void activate( final Object bean );

    // ----------------------------------------------------------------------
    // Implementation methods
    // ----------------------------------------------------------------------

    /**
     * @return Thread-local holder of any pending beans
     */
    static Object[] getPendingHolder()
    {
        Object[] holder = pendingHolder.get();
        if ( null == holder )
        {
            pendingHolder.set( holder = new Object[1] );
        }
        return holder;
    }

    // ----------------------------------------------------------------------
    // Implementation types
    // ----------------------------------------------------------------------

    /**
     * Collects pending beans waiting for activation.
     */
    @SuppressWarnings( "serial" )
    private final class Pending
        extends ArrayList<Object>
    {
        Pending( final Object bean )
        {
            add( bean );
        }

        /**
         * Activates all pending beans in order of registration.
         */
        public void activate()
        {
            for ( int i = 0, size = size(); i < size; i++ )
            {
                BeanScheduler.this.activate( get( i ) );
            }
        }
    }

    /**
     * Listens to provisioning events in order to determine safe activation points.
     */
    static final class Activator
        implements com.google.inject.spi.ProvisionListener
    {
        public <T> void onProvision( final ProvisionInvocation<T> pi )
        {
            final Object[] holder = getPendingHolder();
            if ( null == holder[0] )
            {
                final Object pending;
                holder[0] = PLACEHOLDER;
                try
                {
                    pi.provision(); // may involve nested calls/cycles
                }
                finally
                {
                    pending = holder[0];
                    holder[0] = null;
                }
                if ( pending instanceof Pending )
                {
                    ( (Pending) pending ).activate();
                }
            }
        }
    }
}
