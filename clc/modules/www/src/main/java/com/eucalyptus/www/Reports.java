package com.eucalyptus.www;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ServletException;
import javax.servlet.http.*;

import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.export.*;
import net.sf.jasperreports.engine.xml.JRXmlLoader;

import org.apache.log4j.Logger;
import org.bouncycastle.util.encoders.Base64;

import com.eucalyptus.auth.Accounts;
import com.eucalyptus.auth.principal.User;
import com.eucalyptus.bootstrap.SystemIds;
import com.eucalyptus.cluster.Cluster;
import com.eucalyptus.cluster.Clusters;
import com.eucalyptus.component.Components;
import com.eucalyptus.component.id.Database;
import com.eucalyptus.configurable.ConfigurableClass;
import com.eucalyptus.configurable.ConfigurableField;
import com.eucalyptus.reporting.ReportingCriterion;
import com.eucalyptus.reporting.Period;
import com.eucalyptus.reporting.instance.InstanceReportLine;
import com.eucalyptus.reporting.instance.InstanceReportLineGenerator;
import com.eucalyptus.reporting.s3.S3ReportLine;
import com.eucalyptus.reporting.s3.S3ReportLineGenerator;
import com.eucalyptus.reporting.storage.StorageReportLine;
import com.eucalyptus.reporting.storage.StorageReportLineGenerator;
import com.eucalyptus.reporting.units.Units;
import com.eucalyptus.system.SubDirectory;
import com.google.gwt.user.client.rpc.SerializableException;

import edu.ucsb.eucalyptus.admin.server.*;
import edu.ucsb.eucalyptus.msgs.NodeLogInfo;
import groovy.lang.Binding;
import groovy.util.GroovyScriptEngine;

@ConfigurableClass( root = "reporting", description = "Parameters controlling the generation of system reports." )
public class Reports extends HttpServlet {
  @ConfigurableField( description = "The number of seconds which a generated report should be cached.", initial = "60" )
  public static Integer REPORT_CACHE_SECS = 1200;
  private static Logger LOG               = Logger.getLogger( Reports.class );
  
  private static String STORAGE_REPORT_FILENAME         = "storage.jrxml";
  private static String NESTED_STORAGE_REPORT_FILENAME  = "nested_storage.jrxml";
  private static String INSTANCE_REPORT_FILENAME        = "instance.jrxml";
  private static String NESTED_INSTANCE_REPORT_FILENAME = "nested_instance.jrxml";
  private static String S3_REPORT_FILENAME              = "s3.jrxml";
  private static String NESTED_S3_REPORT_FILENAME       = "nested_s3.jrxml";
  
  enum Param {
    name, type, session, /*page,*/flush( false ), start( false ), end( false ), component( false ),
     cluster( false ), host( false ), criterionId( false ), groupById( false );

    private String  value    = null;
    private Boolean required = Boolean.TRUE;
    
    public Boolean isRequired( ) {
      return this.required;
    }
    
    private Param( String value, Boolean required ) {
      this.value = value;
      this.required = required;
    }
    
    private Param( ) {}
    
    private Param( Boolean required ) {
      this.required = required;
    }
    
    public String get( ) throws NoSuchFieldException {
      if ( this.value == null ) {
        throw new NoSuchFieldException( );
      } else {
        return this.value;
      }
    }
    
    public String get( HttpServletRequest req ) throws IllegalArgumentException {
      if ( req.getParameter( this.name( ) ) == null ) {
        throw new IllegalArgumentException( "'" + this.name( ) + "' is a required argument." );
      } else {
        this.value = req.getParameter( this.name( ) );
        LOG.debug( "Found parameter: " + this.name( ) + "=" + this.value );
        return this.value;
      }
    }
  }
  
  @Override
  protected void doGet( HttpServletRequest req, HttpServletResponse res ) throws ServletException, IOException {
    for ( Param p : Param.values( ) ) {
      try {
        p.get( req );
      } catch ( IllegalArgumentException e ) {
        if ( p.isRequired( ) ) {
          LOG.debug( e, e );
          throw new RuntimeException( e );
        }
      }
    }
    
    try {
      this.verifyUser( Param.session.get( ) );
    } catch ( NoSuchFieldException e ) {
      LOG.debug( e, e );
      throw new RuntimeException( e );
    }
    if ( Param.name.get( req ).startsWith( "service" ) ) {
      this.exportLogs( req, res );
    } else {
      Date startTime = this.parseTime( Param.start );
      Date endTime = this.parseTime( Param.end );
      for ( Param p : Param.values( ) ) {
        try {
          LOG.debug( String.format( "REPORT: %10.10s=%s", p.name( ), p.get( ) ) );
        } catch ( NoSuchFieldException e1 ) {
          LOG.debug( String.format( "REPORT: %10.10s=%s", p.name( ), e1.getMessage( ) ) );
        }
      }
      this.exportReport( req, res );
    }
  }
  
  private void exportLogs( HttpServletRequest req, HttpServletResponse res ) {
    try {
      String component = Param.component.get( req );
      String cluster = Param.cluster.get( req );
      String host = Param.host.get( req );
      LOG.debug( String.format( "LOG:  component=%s  cluster=%s  host=%s", component, cluster, host ) );
      final PrintWriter out = res.getWriter( );
      try {
        res.setContentType( "text/plain" );
        if( "cluster".equals( component ) ) {
          Cluster c = Clusters.getInstance( ).lookup( cluster );
          out.println( "Sending request to: " + c.getUri( ) );
          try {
            NodeLogInfo logInfo = c.getLastLog( );
            if ( logInfo != null && logInfo.getCcLog( ) != null && !"".equals( logInfo.getCcLog( ) ) ) {
              String log = new String( Base64.decode( logInfo.getCcLog( ) ) );
              String printLog = (log.length( )>1024*64)?log.substring( log.length() - 1024*64 ):log;
              out.write( printLog );
              out.flush( );
            } else {
              out.println( "ERROR getting log information for " + host );
              out.println( logInfo.toString( ) );
            }
          } catch ( Throwable e ) {
            LOG.debug( e, e );
            e.printStackTrace( out );
          }
        } else if ( "node".equals( component ) ) {
          Cluster c = Clusters.getInstance( ).lookup( cluster );
          out.println( "Sending request to: " + host );
          try {
            NodeLogInfo logInfo = c.getNodeLog( host );
            if ( logInfo != null && logInfo.getCcLog( ) != null && !"".equals( logInfo.getCcLog( ) ) ) {
              String log = new String( Base64.decode( logInfo.getCcLog( ) ) );
              String printLog = (log.length( )>1024*64)?log.substring( log.length() - 1024*64 ):log;
              out.write( printLog );
              out.flush( );
            } else {
              out.println( "ERROR getting log information for " + host );
              if ( logInfo != null ) {
                out.println( logInfo.toString( ) );
	      }
            }
          } catch ( Throwable e ) {
            LOG.debug( e, e );
            e.printStackTrace( out );
          }
          
        }
        out.close( );
      } catch ( Throwable e ) {
        LOG.debug( e, e );
        e.printStackTrace( out );
        out.close( );
      }
    } catch ( IllegalArgumentException e ) {
      LOG.debug( e, e );
    } catch ( IOException e ) {
      LOG.debug( e, e );
    }
  }
  
  private void exportReport( HttpServletRequest req, HttpServletResponse res ) {
    try {
      Type reportType = Type.valueOf( Param.type.get( ) );
      try {
        Boolean doFlush = this.doFlush( );


        final JRExporter exporter = reportType.setup( req, res, Param.name.get( req ) );
        ReportCache reportCache = getReportManager( Param.name.get( req ), doFlush );
        LOG.info("--> scriptName:" + Param.name.get( req ));

        String scriptName = Param.name.get( req );
        JasperPrint jasperPrint = null;
        if (scriptName.equals("user_vms") || scriptName.equals("user_storage")
        		||  scriptName.equals("user_s3"))
        {

        	long start = Long.parseLong(Param.start.get(req));
        	long end = Long.parseLong(Param.end.get(req));
        	Period period = new Period(start, end);
        	int criterionId = Integer.parseInt(Param.criterionId.get(req));
        	int groupById = Integer.parseInt(Param.groupById.get(req));
        	ReportingCriterion criterion = ReportingCriterion.values()[criterionId+1]; //TODO: explain magic num
        	Units displayUnits = Units.DEFAULT_DISPLAY_UNITS;
 
        	Map<String,String> params = new HashMap<String,String>();
    		params.put("criterion", criterion.toString());
    		params.put("timeUnit", displayUnits.getTimeUnit().toString());
    		params.put("sizeUnit", displayUnits.getSizeUnit().toString());
    		params.put("sizeTimeTimeUnit", displayUnits.getSizeTimeTimeUnit().toString());
    		params.put("sizeTimeSizeUnit", displayUnits.getSizeTimeSizeUnit().toString());
    		
    		ReportingCriterion groupByCriterion =  null;
    		if (groupById > 0) {
				groupByCriterion = ReportingCriterion.values()[groupById-1];
				params.put("groupByCriterion", groupByCriterion.toString());        		
        	}
    		
    		
    		if (scriptName.equals("user_vms")) {

    			InstanceReportLineGenerator generator =
    				InstanceReportLineGenerator.getInstance();
    			File jrxmlFile = null;
    			JRDataSource dataSource = null;
    			if (groupById == 0) {
            		List<InstanceReportLine> list =
            			generator.getReportLines(period, criterion, displayUnits);
    				dataSource = new JRBeanCollectionDataSource(list);
    				jrxmlFile = new File(SubDirectory.REPORTS.toString() + File.separator + INSTANCE_REPORT_FILENAME);
    			} else {
            		List<InstanceReportLine> list =
            			generator.getReportLines(period, groupByCriterion, criterion, displayUnits);
    				dataSource = new JRBeanCollectionDataSource(list);
    				jrxmlFile = new File(SubDirectory.REPORTS.toString() + File.separator + NESTED_INSTANCE_REPORT_FILENAME);
        		}
    			
				JasperReport report =
					JasperCompileManager.compileReport(jrxmlFile.getAbsolutePath());
				jasperPrint =
					JasperFillManager.fillReport(report, params, dataSource);
				
    		} else if (scriptName.equals("user_storage")) {

    			StorageReportLineGenerator generator = StorageReportLineGenerator.getInstance();
    			File jrxmlFile = null;
    			JRDataSource dataSource = null;
            	if (groupById == 0) {
            		List<StorageReportLine> list =
            			generator.getReportLines(period, criterion, displayUnits);
            		dataSource = new JRBeanCollectionDataSource(list);
            		jrxmlFile = new File(SubDirectory.REPORTS.toString() + File.separator + STORAGE_REPORT_FILENAME);
            	} else {
            		List<StorageReportLine> list =
            			generator.getReportLines(period, groupByCriterion, criterion, displayUnits);
            		dataSource = new JRBeanCollectionDataSource(list);
            		jrxmlFile = new File(SubDirectory.REPORTS.toString() + File.separator + NESTED_STORAGE_REPORT_FILENAME);
            	}
    			JasperReport report =
    				JasperCompileManager.compileReport(jrxmlFile.getAbsolutePath());
    			jasperPrint =
    				JasperFillManager.fillReport(report, params, dataSource);
    			
    		} else if (scriptName.equals("user_s3")) {

    			S3ReportLineGenerator generator = S3ReportLineGenerator.getInstance();
    			File jrxmlFile = null;
    			JRDataSource dataSource = null;
            	if (groupById == 0) {
            		List<S3ReportLine> list =
            			generator.getReportLines(period, criterion, displayUnits);
            		dataSource = new JRBeanCollectionDataSource(list);
            		jrxmlFile = new File(SubDirectory.REPORTS.toString() + File.separator + S3_REPORT_FILENAME);
            	} else {
            		List<S3ReportLine> list =
            			generator.getReportLines(period, groupByCriterion, criterion, displayUnits);
            		dataSource = new JRBeanCollectionDataSource(list);
            		jrxmlFile = new File(SubDirectory.REPORTS.toString() + File.separator + NESTED_S3_REPORT_FILENAME);
            	}
    			JasperReport report =
    				JasperCompileManager.compileReport(jrxmlFile.getAbsolutePath());
    			jasperPrint =
    				JasperFillManager.fillReport(report, params, dataSource);
    		}

        } else {
        	
        	/* Load reports using the older mechanism
        	 */
        	jasperPrint = reportCache.getJasperPrint( req );
        }

        exporter.setParameter( JRExporterParameter.JASPER_PRINT, jasperPrint );
        //        exporter.setParameter( JRExporterParameter.PAGE_INDEX, new Integer( Param.page.get( ) ) );
        exporter.exportReport( );
      } catch ( Throwable ex ) {
        LOG.error( ex, ex );
        res.setContentType( "text/plain" );
        LOG.error( "Could not create the report stream " + ex.getMessage( ) + " " + ex.getLocalizedMessage( ) );
        ex.printStackTrace( res.getWriter( ) );
      } finally {
        reportType.close( res );
      }
    } catch ( NoSuchFieldException e ) {
      LOG.debug( e, e );
      this.hasError( "Failed to generate report: " + e.getMessage( ), res );
    } catch ( IOException e ) {
      LOG.debug( e, e );
      this.hasError( "Failed to generate report: " + e.getMessage( ), res );
    }
  }

  private Date parseTime( Param p ) {
    Date time = new Date( );
    try {
      String str = p.get( );
      time = new Date( Long.parseLong( str ) );
    } catch ( IllegalArgumentException e ) {
      LOG.error( e, e );
    } catch ( NoSuchFieldException e ) {
      LOG.error( e, e );
    } catch ( Exception e ) {
      LOG.debug( e, e );
    }
    return time;
  }
  
  private Boolean doFlush( ) {
    try {
      return Boolean.parseBoolean( Param.flush.get( ) );
    } catch ( IllegalArgumentException e ) {
      LOG.debug( e, e );
    } catch ( Exception e ) {
      LOG.debug( e, e );
    }
    return Boolean.FALSE;
  }
  
  private void verifyUser( String sessionId ) {
    SessionInfo session;
    try {
      session = EucalyptusWebBackendImpl.verifySession( sessionId );
      User user = null;
      try {
        user = Accounts.lookupUserByName( session.getUserId( ) );
      } catch ( Exception e ) {
        throw new RuntimeException( "User does not exist" );
      }
      if ( !user.isSystemAdmin( ) ) {
        throw new RuntimeException( "Only administrators can view reports." );
      }
    } catch ( SerializableException e1 ) {
      throw new RuntimeException( "Error obtaining session info." );
    }
    session.setLastAccessed( System.currentTimeMillis( ) );
  }
  
  public static class ReportCache {
    private final long            timestamp;
    private final String          name;
    private final String          reportName;
    private final String          reportGroup;
    private final AtomicBoolean         pending     = new AtomicBoolean( false );
    private Future<JasperPrint>   pendingPrint;
    private JasperPrint           jasperPrint = null;
    private final JasperDesign    jasperDesign;
    private final JasperReport    jasperReport;
    
//    private Callable<JasperPrint> async;
    
    public ReportCache( String name, JasperDesign jasperDesign ) throws JRException {
      this.timestamp = System.currentTimeMillis( ) / 1000;
      this.reportName = jasperDesign.getName( );
      this.jasperDesign = jasperDesign;
      this.reportGroup = jasperDesign.getProperty( "euca.report.group" );
      this.name = name;
//      this.async = new Callable<JasperPrint>( ) {
//        @Override
//        public JasperPrint call( ) throws Exception {
//          try {
//            ReportCache.this.jasperPrint = prepareReport( ReportCache.this );
//            return ReportCache.this.jasperPrint;
//          } catch ( Exception e ) {
//            LOG.error( e, e );
//            throw e;
//          } finally {
//            ReportCache.this.pending.set( false );
//          }
//        }
//      };
      try {
        this.jasperReport = JasperCompileManager.compileReport( jasperDesign );
      } catch ( JRException e1 ) {
        LOG.debug( e1, e1 );
        throw e1;
      }
    }
    
    public String getReportGroup( ) {
      return this.reportGroup;
    }
    
    @Override
    public int hashCode( ) {
      final int prime = 31;
      int result = 1;
      result = prime * result + ( ( this.name == null ) ? 0 : this.name.hashCode( ) );
      return result;
    }
    
    @Override
    public boolean equals( Object obj ) {
      if ( this == obj ) return true;
      if ( obj == null ) return false;
      if ( getClass( ) != obj.getClass( ) ) return false;
      ReportCache other = ( ReportCache ) obj;
      if ( this.name == null ) {
        if ( other.name != null ) return false;
      } else if ( !this.name.equals( other.name ) ) return false;
      return true;
    }
    
    public String getReportName( ) {
      return this.reportName;
    }
    
    public long getTimestamp( ) {
      return this.timestamp;
    }
    
    public String getName( ) {
      return this.name;
    }
    
    public boolean isDone( ) {
      return this.jasperPrint != null || ( !this.pending.get() && this.pendingPrint != null && this.pendingPrint.isDone( ) );
    }
    
    public JasperPrint getJasperPrint(HttpServletRequest req ) throws JRException, SQLException, IOException {
      return this.jasperPrint = Reports.prepareReport( this, req );
    }
    
    public boolean isExpired( ) {
      return ( System.currentTimeMillis( ) / 1000l ) - this.timestamp > REPORT_CACHE_SECS;
    }
    
    public JasperDesign getJasperDesign( ) {
      return this.jasperDesign;
    }
    
    public JasperReport getJasperReport( ) {
      return this.jasperReport;
    }
  }
  
  private static Map<String, ReportCache> reportCache = new ConcurrentHashMap<String, ReportCache>( );
  
  public static ReportCache getReportManager( final String name, boolean flush ) throws JRException, SQLException {
    try {
      if ( !flush && reportCache.containsKey( name ) && !reportCache.get( name ).isExpired( ) ) {
        return reportCache.get( name );
      } else if ( reportCache.containsKey( name ) && ( reportCache.get( name ).isExpired( ) || flush ) ) {
        final JasperDesign jasperDesign = JRXmlLoader.load( SubDirectory.REPORTS.toString( ) + File.separator + name + ".jrxml" );
        reportCache.put( name, new ReportCache( name, jasperDesign ) );
        return reportCache.get( name );
      } else {
        ReportCache r = reportCache.get( name );
        final JasperDesign jasperDesign = JRXmlLoader.load( SubDirectory.REPORTS.toString( ) + File.separator + name + ".jrxml" );
        reportCache.put( name, new ReportCache( name, jasperDesign ) );
      }
      return reportCache.get( name );
    } catch ( Throwable t ) {
      LOG.error( t, t );
      throw new JRException( t );
    }
  }
  
  public static JasperPrint prepareReport( final ReportCache reportCache, final HttpServletRequest req ) throws JRException, SQLException, IOException {
    JasperPrint jasperPrint;
    final boolean jdbc = !( new File( SubDirectory.REPORTS.toString( ) + File.separator + reportCache.getName( ) + ".groovy" ).exists( ) );
    if ( jdbc ) {
      String url = String.format( "jdbc:%s_%s", Components.lookup( Database.class ).getUri( ).toString( ), "records" );
      Connection jdbcConnection = DriverManager.getConnection( url, "eucalyptus", SystemIds.databasePassword( ) );
      jasperPrint = JasperFillManager.fillReport( reportCache.getJasperReport( ), new HashMap() {{
        put( "EUCA_NOT_BEFORE", new Long( Param.start.get( req ) ) );
        put( "EUCA_NOT_AFTER", new Long( Param.end.get( req ) ) );
        put( "EUCA_NOT_BEFORE_DATE", new Date( new Long( Param.start.get( req ) ) ) );
        put( "EUCA_NOT_AFTER_DATE", new Date( new Long( Param.end.get( req ) ) ) );
      }}, jdbcConnection );
    } else {
      FileReader fileReader = null;
      try {
        final List results = new ArrayList( );
        final List groupResults = new ArrayList( );
        fileReader = new FileReader( SubDirectory.REPORTS + File.separator + reportCache.getName( ) + ".groovy" );
        Binding binding = new Binding( new HashMap( ) {
          {
            put( "results", results );
            put( "groupResults", groupResults );
            put( "notBefore", new Long( Param.start.get( req ) ) );
            put( "notAfter", new Long( Param.end.get( req ) ) );
            put( "notBeforeDate", new Date( new Long( Param.start.get( req ) ) ) );
            put( "notAfterDate", new Date( new Long( Param.end.get( req ) ) ) );
          }
        } );
        try {
          new GroovyScriptEngine( SubDirectory.REPORTS.toString( ), ClassLoader.getSystemClassLoader( ) ).run( reportCache.getName( ) + ".groovy", binding );
        } catch ( Exception e ) {
          LOG.error( e, e );
        }
        JRBeanCollectionDataSource data = new JRBeanCollectionDataSource( results );

        jasperPrint = JasperFillManager.fillReport( reportCache.getJasperReport( ), new HashMap() {{
          put( "EUCA_NOT_BEFORE", new Long( Param.start.get( req ) ) );
          put( "EUCA_NOT_AFTER", new Long( Param.end.get( req ) ) );
          put( "EUCA_NOT_BEFORE_DATE", new Date( new Long( Param.start.get( req ) ) ) );
          put( "EUCA_NOT_AFTER_DATE", new Date( new Long( Param.end.get( req ) ) ) );
          put( "EUCA_USER_RESULTS", results );
          put( "EUCA_GROUP_RESULTS", groupResults );
        }}, data );
      } catch ( Throwable e ) {
        LOG.debug( e, e );
        throw new RuntimeException( e );
      } finally {
        if ( fileReader != null ) try {
          fileReader.close( );
        } catch ( IOException e ) {
          LOG.error( e, e );
          throw e;
        }
      }
    }
    return jasperPrint;
  }
    
  public static void hasError( String message, HttpServletResponse response ) {
    try {
      response.getWriter( ).print( EucalyptusManagement.getError( message ) );
      response.getWriter( ).flush( );
    } catch ( IOException e ) {
      e.printStackTrace( );
    }
  }
  
  enum Type {
    pdf {
      @Override
      public JRExporter setup( HttpServletRequest request, HttpServletResponse res, String name ) throws IOException {
        res.setContentType( "application/pdf" );
        res.setHeader( "Content-Disposition", "file; filename=" + name + ".pdf" );
        JRExporter exporter = new JRPdfExporter( );
        exporter.setParameter( JRExporterParameter.OUTPUT_STREAM, res.getOutputStream( ) );
        return exporter;
      }
    },
    csv {
      @Override
      public JRExporter setup( HttpServletRequest request, HttpServletResponse res, String name ) throws IOException {
        res.setContentType( "text/plain" );
        res.setHeader( "Content-Disposition", "file; filename=" + name + ".csv" );
        JRExporter exporter = new JRCsvExporter( );
        exporter.setParameter( JRExporterParameter.OUTPUT_STREAM, res.getOutputStream( ) );
        return exporter;
      }
    },
    html {
      @Override
      public JRExporter setup( HttpServletRequest request, HttpServletResponse res, String name ) throws IOException {
        PrintWriter out = res.getWriter( );
        res.setContentType( "text/html" );
        JRExporter exporter = new JRHtmlExporter( );
        exporter.setParameter( new JRExporterParameter( "EUCA_WWW_DIR" ) {}, "/" );
        exporter.setParameter( JRExporterParameter.OUTPUT_WRITER, res.getWriter( ) );
        exporter.setParameter( JRHtmlExporterParameter.IS_REMOVE_EMPTY_SPACE_BETWEEN_ROWS, Boolean.TRUE );
        exporter.setParameter( JRHtmlExporterParameter.IS_USING_IMAGES_TO_ALIGN, Boolean.FALSE );
        exporter.setParameter( JRHtmlExporterParameter.IGNORE_PAGE_MARGINS, Boolean.TRUE );
        return exporter;
      }
      
      @Override
      public void close( HttpServletResponse res ) throws IOException {
        res.getWriter( ).close( );
      }
    },
    xls {
      @Override
      public JRExporter setup( HttpServletRequest request, HttpServletResponse res, String name ) throws IOException {
        res.setContentType( "application/vnd.ms-excel" );
        res.setHeader( "Content-Disposition", "file; filename=" + name + ".xls" );
        JRExporter exporter = new JRXlsExporter( );
        exporter.setParameter( JRExporterParameter.OUTPUT_STREAM, res.getOutputStream( ) );
        return exporter;
      }
    };
    public abstract JRExporter setup( HttpServletRequest request, HttpServletResponse res, String name ) throws IOException;
    
    public void close( HttpServletResponse res ) throws IOException {
      res.getOutputStream( ).close( );
    }
  }
  
}
