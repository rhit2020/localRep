import java.io.InputStream;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServlet;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;

public class RecConfigManager {
    
	/*
	 *  this is the variable that holds the threshold for proactive recommendation of examples. 
	 *  only examples that have the recommendation scores of greater than the threshold will be sent to the Aggregate UI.
	 */
	public double rec_score_threshold; 
	// database connection parameters
    public String rec_dbstring;
    public String rec_dbuser;
    public String rec_dbpass;
	public int example_count_personalized_approach;
    private static String config_string = "./WEB-INF/rec_config.xml";

    public RecConfigManager(HttpServlet servlet) {
        try {
            ServletContext context = servlet.getServletContext();
            // System.out.println(context.getContextPath());
            InputStream input = context.getResourceAsStream(config_string);
            if (input != null) {
                DocumentBuilderFactory dbFactory = DocumentBuilderFactory
                        .newInstance();
                DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                Document doc = dBuilder.parse(input);
                doc.getDocumentElement().normalize();

                rec_dbstring = doc.getElementsByTagName("rec_dbstring").item(0)
                        .getTextContent().trim().toLowerCase();
                rec_dbuser = doc.getElementsByTagName("rec_dbuser").item(0)
                        .getTextContent().trim().toLowerCase();
                rec_dbpass = doc.getElementsByTagName("rec_dbpass").item(0)
                        .getTextContent().trim().toLowerCase();
                try{
                	rec_score_threshold = Double.parseDouble(doc.getElementsByTagName("rec_score_threshold")
                            .item(0).getTextContent().trim());                    
                }catch(Exception e){
                	rec_score_threshold = 0.7;
                }
                try{
                	example_count_personalized_approach = Integer.parseInt(doc.getElementsByTagName("example_count_personalized_approach")
                            .item(0).getTextContent().trim());                    
                }catch(Exception e){
                	example_count_personalized_approach = 10;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
