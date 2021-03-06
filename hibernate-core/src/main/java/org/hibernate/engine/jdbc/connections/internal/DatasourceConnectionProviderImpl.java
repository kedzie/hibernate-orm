/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.engine.jdbc.connections.internal;

import java.io.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.hibernate.HibernateException;
import org.hibernate.cfg.Environment;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.jndi.spi.JndiService;
import org.hibernate.service.UnknownUnwrapTypeException;
import org.hibernate.service.spi.Configurable;
import org.hibernate.service.spi.InjectService;
import org.hibernate.service.spi.Stoppable;

/**
 * A {@link org.hibernate.engine.jdbc.connections.spi.ConnectionProvider} that manages connections from an underlying {@link DataSource}.
 * <p/>
 * The {@link DataSource} to use may be specified by either:<ul>
 * <li>injection via {@link #setDataSource}</li>
 * <li>decaring the {@link DataSource} instance using the {@link Environment#DATASOURCE} config property</li>
 * <li>decaring the JNDI name under which the {@link DataSource} can be found via {@link Environment#DATASOURCE} config property</li>
 * </ul>
 *
 * @author Gavin King
 * @author Steve Ebersole
 */
public class DatasourceConnectionProviderImpl implements ConnectionProvider, Configurable, Stoppable, Externalizable {
	private DataSource dataSource;
	private String datasourceJndiName;
	private String user;
	private String pass;
	private boolean useCredentials;
	private JndiService jndiService;

	private boolean available;

	public DataSource getDataSource() {
		return dataSource;
	}

	public void setDataSource(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@InjectService( required = false )
	@SuppressWarnings("UnusedDeclaration")
	public void setJndiService(JndiService jndiService) {
		this.jndiService = jndiService;
	}

	@Override
	public boolean isUnwrappableAs(Class unwrapType) {
		return ConnectionProvider.class.equals( unwrapType ) ||
				DatasourceConnectionProviderImpl.class.isAssignableFrom( unwrapType ) ||
				DataSource.class.isAssignableFrom( unwrapType );
	}

	@Override
	@SuppressWarnings( {"unchecked"})
	public <T> T unwrap(Class<T> unwrapType) {
		if ( ConnectionProvider.class.equals( unwrapType ) ||
				DatasourceConnectionProviderImpl.class.isAssignableFrom( unwrapType ) ) {
			return (T) this;
		}
		else if ( DataSource.class.isAssignableFrom( unwrapType ) ) {
			return (T) getDataSource();
		}
		else {
			throw new UnknownUnwrapTypeException( unwrapType );
		}
	}

	@Override
	public void configure(Map configValues) {
		if ( this.dataSource == null ) {
			final Object dataSource = configValues.get( Environment.DATASOURCE );
			if ( DataSource.class.isInstance( dataSource ) ) {
				this.dataSource = (DataSource) dataSource;
			}
			else {
				datasourceJndiName = (String) dataSource;
			}
		}
		user = (String) configValues.get( Environment.USER );
		pass = (String) configValues.get( Environment.PASS );
		available = true;
		initTransients();
	}

	private void initTransients() {
		if ( this.dataSource == null ) { //Lookup dataSource from JNDI if not injected
			if ( datasourceJndiName == null ) {
				throw new HibernateException(
						"DataSource to use was not injected nor specified by [" + Environment.DATASOURCE
								+ "] configuration property"
				);
			}
			if ( jndiService == null ) {
				throw new HibernateException( "Unable to locate JndiService to lookup Datasource" );
			}
			this.dataSource = (DataSource) jndiService.locate( datasourceJndiName );
		}
		if ( this.dataSource == null ) {
			throw new HibernateException( "Unable to determine appropriate DataSource to use" );
		}
		useCredentials = user != null || pass != null;
	}

	@Override
	public void stop() {
		available = false;
		dataSource = null;
	}

	@Override
	public Connection getConnection() throws SQLException {
		if ( !available ) {
			throw new HibernateException( "Provider is closed!" );
		}
		return useCredentials ? dataSource.getConnection( user, pass ) : dataSource.getConnection();
	}

	@Override
	public void closeConnection(Connection connection) throws SQLException {
		connection.close();
	}

	@Override
	public boolean supportsAggressiveRelease() {
		return true;
	}

	@Override public void writeExternal(ObjectOutput out) throws IOException {
		out.writeBoolean(available);
		out.writeBoolean(user!=null);
		if(user!=null)
			out.writeUTF(user);
		out.writeBoolean(pass!=null);
		if(pass!=null)
			out.writeUTF(pass);
		out.writeBoolean(jndiService!=null);
		if(jndiService!=null)
			out.writeObject(jndiService);
		if(available) {
			out.writeUTF(datasourceJndiName);
			if (datasourceJndiName == null) {  //only serialize injected, as opposed to looked up, datasource
				out.writeBoolean(dataSource!=null);
				if(dataSource!=null)
					out.writeObject(dataSource);
			}
		}
	}

	@Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
		available = in.readBoolean();
		if(in.readBoolean())
			user = in.readUTF();
		if(in.readBoolean())
			pass = in.readUTF();
		if(in.readBoolean())
			jndiService = (JndiService) in.readObject();
		if( available ) { //init transients
			datasourceJndiName = in.readUTF();
			if ( datasourceJndiName == null && in.readBoolean() ) {
				dataSource = (DataSource) in.readObject();
			}
			initTransients();
		}
	}
}
