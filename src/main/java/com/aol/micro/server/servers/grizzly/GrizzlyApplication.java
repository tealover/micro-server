package com.aol.micro.server.servers.grizzly;

import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.servlet.ServletContextListener;

import lombok.Getter;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.accesslog.AccessLogBuilder;
import org.glassfish.grizzly.servlet.FilterRegistration;
import org.glassfish.grizzly.servlet.ServletRegistration;
import org.glassfish.grizzly.servlet.WebappContext;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.ContextLoaderListener;

import com.aol.micro.server.ErrorCode;
import com.aol.micro.server.auto.discovery.FilterConfiguration;
import com.aol.micro.server.auto.discovery.ServletConfiguration;
import com.aol.micro.server.rest.JerseySpringIntegrationContextListener;
import com.aol.micro.server.servers.AccessLogLocationBean;
import com.aol.micro.server.servers.ServerApplication;
import com.aol.micro.server.servers.model.FilterData;
import com.aol.micro.server.servers.model.ServerData;
import com.aol.micro.server.servers.model.ServletData;

public class GrizzlyApplication  implements ServerApplication {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	@Getter
	private final ServerData serverData;

	public GrizzlyApplication(ServerData serverData) {
		this.serverData = serverData;
	}

	public void run(CompletableFuture start,CompletableFuture end) {

		WebappContext webappContext = new WebappContext("WebappContext", "");

		addServlet(webappContext);

		addServlets(webappContext);

		addFilters(webappContext);

		addListeners(webappContext);

		HttpServer httpServer = HttpServer.createSimpleServer(null, "0.0.0.0",
				serverData.getPort());

		addAccessLog(httpServer);

		startServer(webappContext, httpServer,start,end);
	}

	private void startServer(WebappContext webappContext, HttpServer httpServer, CompletableFuture start, CompletableFuture end)  {
		webappContext.deploy(httpServer);
		try {
			logger.info("Starting application {} on port {}", serverData
					.getModule().getContext(), serverData.getPort());
			logger.info("Browse to http://localhost:{}/{}/application.wadl",
					serverData.getPort(), serverData.getModule().getContext());
			httpServer.start();
			start.complete(true);
			end.get();
			
		} catch (IOException e) {
			throw new RuntimeException(e);
		} catch (ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		} finally {
			httpServer.stop();
		}
	}

	private void addAccessLog(HttpServer httpServer) {
		try {
			String accessLogLocation = serverData.getRootContext()
					.getBean(AccessLogLocationBean.class)
					.getAccessLogLocation();
			final AccessLogBuilder builder = new AccessLogBuilder(
					accessLogLocation + serverData.getModule().getContext()
							+ "-access.log");
			builder.rotatedDaily();
			builder.rotationPattern("yyyy-MM-dd");
			builder.instrument(httpServer.getServerConfiguration());
		} catch (Exception e) {
			logger.error(ErrorCode.SERVER_STARTUP_FAILED_TO_CREATE_ACCESS_LOG
					.toString() + ": " + e.getMessage());
			if (e.getCause() != null)
				logger.error("CAUSED BY: "
						+ ErrorCode.SERVER_STARTUP_FAILED_TO_CREATE_ACCESS_LOG
								.toString() + ": " + e.getCause().getMessage());

		}

	}

	private void addServlet(WebappContext webappContext) {
		ServletContainer container = new ServletContainer();
		ServletRegistration servletRegistration = webappContext.addServlet(
				"Jersey Spring Web Application", container);
		servletRegistration.setInitParameter("javax.ws.rs.Application",
				this.serverData.getModule().getJaxWsRsApplication());
		servletRegistration.setInitParameter(
				"jersey.config.server.provider.packages", this.serverData
						.getModule().getProviders());
		servletRegistration.setLoadOnStartup(1);
		servletRegistration.addMapping(serverData.getBaseUrlPattern());
	}

	private void addFilters(WebappContext webappContext) {

		addExplicitlyDeclaredFilters(webappContext);
		addAutoDiscoveredFilters(webappContext);

	}

	private void addAutoDiscoveredFilters(WebappContext webappContext) {
		serverData
				.getRootContext()
				.getBeansOfType(FilterConfiguration.class)
				.values()
				.forEach(
						filter -> setInitParameters(
								webappContext.addFilter(getName(filter),
										getClass(filter)), filter)
								.addMappingForUrlPatterns(
										EnumSet.allOf(DispatcherType.class),
										filter.getMapping()));
	}

	private void addExplicitlyDeclaredFilters(WebappContext webappContext) {
		for (FilterData filterData : serverData.getFilterDataList()) {
			FilterRegistration filterReg = webappContext.addFilter(
					filterData.getFilterName(), filterData.getFilter());
			filterReg.addMappingForUrlPatterns(
					EnumSet.allOf(DispatcherType.class),
					filterData.getMapping());
		}
	}

	private Class<? extends Filter> getClass(FilterConfiguration filter) {
		if (filter.getClass() != null)
			return filter.getFilter();
		return (Class<? extends Filter>) filter.getClass();
	}

	private FilterRegistration setInitParameters(FilterRegistration addFilter,
			FilterConfiguration filter) {
		addFilter.setInitParameters(filter.getInitParameters());
		return addFilter;
	}

	private String getName(FilterConfiguration filter) {
		if (filter.getName() != null)
			return filter.getName();
		return filter.getClass().getName();
	}

	private void addServlets(WebappContext webappContext) {
		addExplicitlyDeclaredServlets(webappContext);
		addAutoDiscoveredServlets(webappContext);
	}

	private void addAutoDiscoveredServlets(WebappContext webappContext) {
		serverData
				.getRootContext()
				.getBeansOfType(ServletConfiguration.class)
				.values()
				.forEach(
						servlet -> {
							setInitParameters(
									webappContext.addServlet(getName(servlet),
											getServlet(servlet)), servlet)
									.addMapping(servlet.getMapping());
							logServlet(servlet);
						});
	}

	private void addExplicitlyDeclaredServlets(WebappContext webappContext) {
		for (ServletData servletData : serverData.getServletDataList()) {
			ServletRegistration filterReg = webappContext.addServlet(
					servletData.getServletName(), servletData.getServlet());
			filterReg.addMapping(servletData.getMapping());
		}
	}

	private void logServlet(ServletConfiguration servlet) {
		logger.info("Registering servlet on " + servlet.getMapping()[0]);
		;
	}

	private Class<? extends Servlet> getServlet(ServletConfiguration servlet) {
		if (servlet.getServlet() != null)
			return servlet.getServlet();
		return (Class<? extends Servlet>) servlet.getClass();
	}

	private ServletRegistration setInitParameters(
			ServletRegistration addServlet, ServletConfiguration servlet) {
		addServlet.setInitParameters(servlet.getInitParameters());
		return addServlet;
	}

	private String getName(ServletConfiguration servlet) {
		if (servlet.getName() != null)
			return servlet.getName();
		return servlet.getClass().getName();
	}

	private void addListeners(WebappContext webappContext) {
		webappContext.addListener(new ContextLoaderListener(serverData
				.getRootContext()));
		webappContext.addListener(new JerseySpringIntegrationContextListener(serverData));
		serverData.getRootContext()
				.getBeansOfType(ServletContextListener.class).values()
				.forEach(listener -> webappContext.addListener(listener));
	}

}