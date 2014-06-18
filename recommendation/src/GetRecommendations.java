

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;


/**
 * Servlet implementation class GetRecommendations
 */
@WebServlet("/GetRecommendations")
public class GetRecommendations extends HttpServlet {
	private static final long serialVersionUID = 1L;
	//TODO later change the server to adapt2
	//private String server = "http://adapt2.sis.pitt.edu";
    private String server = "http://localhost:8080";
	private String examplesActivityServiceURL = server
            + "/aggregateUMServices/GetExamplesActivity";
	private String questionsActivityServiceURL = server
            + "/aggregateUMServices/GetQuestionsActivity";
	private String contentKCURL = server
	            + "/aggregateUMServices/GetContentConcepts";
	private String conceptLevelsServiceURL = server + "/cbum/ReportManager";


  
    private RecDB rec_db;
    public static DecimalFormat df4 = new DecimalFormat("#.####");
    private RecConfigManager rec_cm = new RecConfigManager(this);
    /**
     * @see HttpServlet#HttpServlet()
     */
    public GetRecommendations() {
        super();
    }
    
    public void openDBConnections() {
    	rec_db = new RecDB(rec_cm.rec_dbstring, rec_cm.rec_dbuser, rec_cm.rec_dbpass);
    	rec_db.openConnection();
    }

    public void closeDBConnections() {
    	rec_db.closeConnection();
    }


	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		
		response.setContentType("application/json");
		//TODO seq_id should be defined for storing the item in the db
		String seq_id = "";
		//TODO forced should be defined
		int forced = -1;
		//TODO method should be identified;
		String[] methods = {"CSSIM","PCSSIM","CONCSIM","PCONCSIM"};
		int method_selected = 0;
		
		
		String usr = request.getParameter("usr"); // all / user / class
	    String grp = request.getParameter("grp"); // the class mnemonic (as
		String sid = request.getParameter("sid"); 
	    String cid = request.getParameter("cid"); 
	    String domain = request.getParameter("domain"); 
	    String lastContentId = request.getParameter("lastContentId"); 
	    String lastContentResult = request.getParameter("lastContentResult"); 
	    //String lastContentProvider = request.getParameter("lastContentProvider"); 
	    int maxRecommendations = 3; // this is the default
	    try {
	    	maxRecommendations = Integer.parseInt(request.getParameter("maxRecommendations")); 
	    }catch(NumberFormatException e){}

	    String[] contentList = request.getParameter("contents").split(","); //contents are separated by ,
        HashMap<String, String[]> examples_activity = this.getUserExamplesActivity(usr, domain);
        HashMap<String, String[]> questions_activity = this.getUserQuestionsActivity(usr, domain);
        HashMap<String, double[]> kcSummary; // knowledge components (concepts) and the level of knowledge of the user in each of them
        //GET THE LEVELS OF KNOWLEDGE OF THE USER IN CONCEPTS
        // FROM USER MODEL USING THE USER MODEL INTERFACE
        kcSummary = this.getConceptLevels(usr, domain, grp); // Concept Knowledge levels
        

        // fill the hashmap kcByContent with content_name, arraylist of [concept_name, weight, direction]
        HashMap<String, ArrayList<String[]>> kcByContent; // for each content there is an array list of kc (concepts) with id, weight (double) and direction (prerequisite/outcome)
        kcByContent = getContentKCs(domain,contentList);
        //Step 1: generate reactive recommendation
        ArrayList<ArrayList<String>> recList = generateRecommendations(
    			seq_id, usr, grp, cid, 
    			sid, lastContentId, lastContentResult, maxRecommendations, 
    			methods, method_selected,
    			examples_activity,
    			questions_activity, contentList, kcByContent,forced);
        //Step 2: generate proactive recommendation
    	HashMap<String,Double> contentSequencingMap = calculateSequenceRank(
    			kcByContent,kcSummary,examples_activity,questions_activity);
        //create the JSON for the reactive/passive Recommendation
        String output = "rec: {\n";
        output += getReactiveJSON(recList);
        output = getProactiveJSON(contentSequencingMap);
        output += "\n}";
        PrintWriter out = response.getWriter();
        out.print(output);
        System.out.println(output);
	}

	private String getProactiveJSON(HashMap<String, Double> contentSequencingMap) {
		String json = "  proative: {\n";
		for (String content : contentSequencingMap.keySet())
		{
			json += "\n    { content: \""+content+"\", score: \""+contentSequencingMap.get(content)+"\" },";
		}
		json += "\n  }";
		return json;
	}

	private String getReactiveJSON(ArrayList<ArrayList<String>> recList) {
		String json = "  reative: {\n";
		for (ArrayList<String> rec : recList)
		{
			json += "\n    { id: \""+rec.get(0)+"\", content: \""+rec.get(1)+"\", score: \""+rec.get(2)+"\" },";
		}
		json += "\n  }";
		return json;
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}
	
	/*
	 * @author : Roya Hosseini 
	 * This method generates the recommendations for the given user at the given time.
	 * Parameters:
	 * - seq_rec_id: is the the id for all recommendations generated by different methods for the given user at given time
	 * - user_id: the id of the user
	 * - group_id: the group id for the user's group
	 * - course_id: is the id of the course
	 * - session_id: is the id of the user's session
	 * - last_content_id: is the rdfid of the the activity (question) that the user has failed
	 * - last_content_res: is the result of the activity (question) with the rdfid equal to last_content_id 
	 * - n: is the number of recommendation generated by each method
	 * - topic_content: is the map containing the keys as topic_names and values as the rdfid of topic activities (questions,examples,readings). @see:guanjieDBInterface.getTopicContent(String)
	 * - examples_activity: is the maps containing the keys as examples and values as the user actions in example. @see um2DBInterface.getUserExamplesActivity(String)
	 * - questions_activity: is the map with the keys as activities and values as the number of success and attempts in the activity. @see: um2DBInterface.getUserQuestionsActivity(String)
	 * - forced :  0 when recommendations were generated in a real condition(the user failed a question), and 1 when the recommendations were generated for rating and the user never failed the question 
	 * Returns: List of recommended example. Each element is a list with the following items:
	 * 1) item_rec_id from the ent_recommendation table
	 * 2) example name 
	 * 3) similarity value
	 */
	
	public ArrayList<ArrayList<String>> generateRecommendations(
			String seq_id, String user_id, String group_id, String course_id, 
			String session_id, String last_content_id, String last_content_res, int n, 
			String[] methods, int method_selected,
			HashMap<String,String[]> examples_activity,
			HashMap<String,String[]> questions_activity,String[] contentList, HashMap<String, ArrayList<String[]>> kcByContent, int forced){
		
        openDBConnections();
		SortedMap<String,Double> exampleMap = null;
		ArrayList<ArrayList<String>> recommendation_list = new ArrayList<ArrayList<String>>();

		for (String method : methods)
		{
			if ( method.equals("CONCSIM") | method.equals("CSSIM"))
			{
				exampleMap = rec_db.getSimilarExamples(method,last_content_id,contentList,n,rec_cm.rec_score_threshold);
			}
			//personalized approaches
			else if (method.equals("PCSSIM") | method.equals("PCONCSIM"))
			{
				String approach = "CSSIM";
				if (method.equals("PCONCSIM"))
					approach = "CONCSIM";
				exampleMap = rec_db.getSimilarExamples(approach,last_content_id,contentList,rec_cm.example_count_personalized_approach,rec_cm.rec_score_threshold);				
				exampleMap = getPersonalizedRecommendation(method,exampleMap, questions_activity, kcByContent,n);			
			}			
			else 
				continue;	
	        int shown = 0;
			if (forced == 1)
				shown = -1; 
			else if (method_selected == -1)
				shown = 0;
			else if (method.equalsIgnoreCase(methods[method_selected]))
				shown = 1;
			recommendation_list.addAll(createRecList(seq_id, user_id, group_id,session_id,
					                           last_content_id,exampleMap, method, shown));
		}
		closeDBConnections();
		return recommendation_list;
	}
	
    // CALLING A UM SERVICE
    public HashMap<String, String[]> getUserExamplesActivity(String usr,
            String domain) {
        HashMap<String, String[]> eActivity = null;
        try {
            String url = examplesActivityServiceURL + "?usr=" + usr;
            JSONObject json = readJsonFromUrl(url);

            if (json.has("error")) {
                System.out
                        .println("Error:[" + json.getString("errorMsg") + "]");
            } else {
                eActivity = new HashMap<String, String[]>();
                JSONArray activity = json.getJSONArray("activity");

                for (int i = 0; i < activity.length(); i++) {
                    JSONObject jsonobj = activity.getJSONObject(i);
                    String[] act = new String[4];
                    act[0] = jsonobj.getString("content_name");
                    act[1] = jsonobj.getDouble("nactions") + "";
                    act[2] = jsonobj.getDouble("distinctactions") + "";
                    act[3] = jsonobj.getDouble("totallines") + "";
                    eActivity.put(act[0], act);

                    // System.out.println(jsonobj.getString("name"));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return eActivity;
    }
    
    private static String readAll(Reader rd) throws IOException {
        StringBuilder sb = new StringBuilder();
        int cp;
        while ((cp = rd.read()) != -1) {
            sb.append((char) cp);
        }
        return sb.toString();
    }

    public static JSONObject readJsonFromUrl(String url) throws IOException,
            JSONException {
        InputStream is = new URL(url).openStream();
        JSONObject json = null;
        try {
            BufferedReader rd = new BufferedReader(new InputStreamReader(is,
                    Charset.forName("UTF-8")));
            String jsonText = readAll(rd);
            json = new JSONObject(jsonText);
        } finally {
            is.close();
        }
        return json;
    }
    
    public HashMap<String, String[]> getUserQuestionsActivity(String usr,
            String domain) {
        HashMap<String, String[]> qActivity = null;
        try {
            String url = questionsActivityServiceURL + "?usr=" + usr;
            JSONObject json = readJsonFromUrl(url);

            if (json.has("error")) {
                System.out
                        .println("Error:[" + json.getString("errorMsg") + "]");
            } else {
                qActivity = new HashMap<String, String[]>();
                JSONArray activity = json.getJSONArray("activity");

                for (int i = 0; i < activity.length(); i++) {
                    JSONObject jsonobj = activity.getJSONObject(i);
                    String[] act = new String[3];
                    act[0] = jsonobj.getString("content_name");
                    act[1] = jsonobj.getDouble("nattempts") + "";
                    act[2] = jsonobj.getDouble("nsuccesses") + "";
                    qActivity.put(act[0], act);
                    // System.out.println(jsonobj.getString("name"));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return qActivity;
    }
    
    private ArrayList<ArrayList<String>> createRecList(String seq_rec_id, String user_id,
			String group_id, String session_id, String last_content_id,			
			SortedMap<String, Double> exampleMap, String method, int shown) {
		double sim;
		ArrayList<ArrayList<String>> recommendation_list = new ArrayList<ArrayList<String>>();
    	for (String ex : exampleMap.keySet())
		{
			sim = exampleMap.get(ex);
    		int id = rec_db.addRecommendation(seq_rec_id, user_id, group_id, session_id,
					last_content_id, ex, method,sim, shown);
			//TODO double check
			if (shown == 1 | shown == -1)
			{
				ArrayList<String> rec = new ArrayList<String>();
				rec.add("" + id); // item_rec_id from the ent_recommendation table
				rec.add(ex); // example rdfid 
				rec.add(df4.format(sim)); // similarity value
			    recommendation_list.add(rec);				
			}				
		}
    	return recommendation_list;
	}


	/* @author: Roya Hosseini
	 * This method re-ranks the top examples selected by the CSS recommendation method using user model
	 * Parameters:
	 * - exampleMap: the map with the key as the top selected examples and the values as their corresponding similarity
	 * - questions_activity: @see guanjieDBInterface.generateRecommendations javaDoc
	 * - limit: @see guanjieDBInterface.generateRecommendations javaDoc
	 * Returns:
	 * - a descendingly sortedmap of examples with their similarity 
	 */
	public SortedMap<String, Double> getPersonalizedRecommendation(String method,SortedMap<String,Double> exampleMap,
			   								  HashMap<String,String[]> questions_activity, HashMap<String, ArrayList<String[]>> kcByContent, int limit)
	{
		Map<String, Double> rankMap = new HashMap<String,Double>();
	    ValueComparator vc =  new ValueComparator(rankMap);
		TreeMap<String,Double> sortedRankMap = new TreeMap<String,Double>(vc);
		ArrayList<String[]> conceptList = new ArrayList<String[]>();
		
		int k = 0;
		int n = 0;
		int s = 0;
		for (String e : exampleMap.keySet())
		{
			k = 0; //number of known concepts in example
			n = 0;//number of new concepts in example
			s = 0;//number of shady concepts in example	
			conceptList = kcByContent.get(e);
			for (String[] c : conceptList) {
				double nsuccess = 0;
				double totalAtt = 0;
				boolean hasAttempt = false;
				List<String> activityList = getActivitiesWithConcept(c[0],kcByContent);//c[0] has the concept name

				for (String a : activityList) {
					if (questions_activity.containsKey(a)) {
						String[] x = questions_activity.get(a);
						totalAtt += Double.parseDouble(x[1]); // x[1] = nattempt
						nsuccess += Double.parseDouble(x[2]); // x[2] = nsuccess
						hasAttempt = true;
					}
				}
				if (hasAttempt == false)
				{
					  n++;	
				}
				else {
					if (nsuccess > (totalAtt/2))
						k++;
					else
						s++;
				}
			}
			double rank = 0.0;
			if (conceptList.size() == 0)
				rank = 0.0;
			else if (k==0 & (s+n)> 3.0)
			{
				rank = -(s+n);
			}
			else
				rank = (3 - (s+n)) * Math.pow((double) k / (double) conceptList.size(), (3 - (s+n)));
			double alpha = 0.0;
			if (method.equals("PCSSIM"))
				alpha = 0.5;
			else if (method.equals("PCONCSIM"))
				alpha = 0.5;
			double combinedMeasure = (1-alpha)*rank+(alpha*exampleMap.get(e));
			rankMap.put(e, combinedMeasure);
		}
		sortedRankMap.putAll(rankMap);
		
		return getTopEntries(limit, sortedRankMap);	
		}

	public static  SortedMap<String,Double> getTopEntries(int limit, SortedMap<String,Double> source) {
		  int count = 0;
		  TreeMap<String,Double> map = new TreeMap<String,Double>();
		  for (Map.Entry<String,Double> entry:source.entrySet())
		  {
		     if (count >= limit)
		    	 break;
		     map.put(entry.getKey(),entry.getValue());
		     count++;
		  }
		  return map;
		}

    private List<String> getActivitiesWithConcept(String concept, HashMap<String, ArrayList<String[]>> kcByContent ) {
		List<String> activities = new ArrayList<String>();
		for (String content: kcByContent.keySet())
		{
			if (kcByContent.get(content).contains(concept))
			{
				if (activities.contains(content) == false)
					activities.add(content);
			}
		}
		return activities;
	}
    
    private HashMap<String,Double> calculateSequenceRank(
			HashMap<String, ArrayList<String[]>> content_concepts,
			Map<String, double[]> user_concept_knowledge_levels,
			HashMap<String, String[]> examples_activity,
			HashMap<String, String[]> questions_activity) {

		HashMap<String,Double> contentPrerequisiteKnowledgeMap = new HashMap<String,Double>();
		HashMap<String,Double> contentImpactMap = new HashMap<String,Double>();
		HashMap<String,Double> contentUnlearnedRatioMap = new HashMap<String,Double>();
		HashMap<String,Double> contentSequencing = new HashMap<String,Double>();
		/* step1: calculate the prerequisite knowledge of the student in the
		 * contents; The results will be stored in the map
		 * contentPrerequisiteKnowledgeMap. 
		 * Also calculate the impact of the content, here by the impact we focus on the concepts that
		   are outcome of the content. We calculate how much the learner still need to know in each of the outcomes
		   and this will form the content impact. The results will be stored in the map contentImpactMap 
		 *  @see document of optimizer */		
		double prerequisiteKnowledgeRatio = 0.0;
		double dividendPrerequisite = 0.0;
		double denominatorPrerequisite = 0.0;

		double impactRatio = 0.0;
		double dividendImpact = 0.0;
		double denominatorImpact = 0.0;

		double weight = 0.0;		
		String content_name;
		ArrayList<String[]> conceptList;

		for (Entry<String, ArrayList<String[]>> entry : content_concepts.entrySet())
		{
			content_name = entry.getKey();
			conceptList = entry.getValue(); //[0] concept, [1] weight, [2] direction

			dividendPrerequisite = 0.0;
			denominatorPrerequisite = 0.0;
			dividendImpact = 0.0;
			denominatorImpact = 0.0;
			prerequisiteKnowledgeRatio = 0.0;
			impactRatio = 0.0;

			for (String[] concept : conceptList)
			{
				weight = 0.0;
				double klevel = 0.0;
				if(user_concept_knowledge_levels != null && user_concept_knowledge_levels.get(concept[0]) != null) 
					klevel = user_concept_knowledge_levels.get(concept[0])[0]; //TODO currently concept level has only 1 value
				try
				{
					weight = Double.parseDouble(concept[1]);
				}catch(Exception e){}

				if (concept[2].equals("prerequisite"))
				{					
					dividendPrerequisite += klevel * Math.log10(weight);
					denominatorPrerequisite += Math.log10(weight);					
				}
				else if (concept[2].equals("outcome"))
				{
					dividendImpact += (1-klevel) * Math.log10(weight);
					denominatorImpact += Math.log10(weight);					
				}				
			}

			if (denominatorPrerequisite != 0)
				prerequisiteKnowledgeRatio = dividendPrerequisite / denominatorPrerequisite;
			contentPrerequisiteKnowledgeMap.put(content_name,prerequisiteKnowledgeRatio);	

			if (denominatorImpact != 0)
				impactRatio = dividendImpact / denominatorImpact;
			contentImpactMap.put(content_name,impactRatio);	
		}		

		/* step2: calculate the value of how much the student has not learned about each content.
		   The results will be stored in the contentUnlearnedRatioMap.
		   To this end, for questions we use the total number of times the user tried
		   each of the questions and also the number of success in each of the them.
		   For examples, we use the distinct lines viewed and the total lines in each of the examples.
		 */

		double unlearnedRatio = 0.0;

		//for questions
		double attempt = 0.0;
		double success = 0.0;
		for(Entry<String, String[]> entry: questions_activity.entrySet()){
			String question = entry.getKey();
			String[] questionInfo = entry.getValue();
			attempt = 0.0;
			success = 0.0;
			try
			{
				attempt = Double.parseDouble(questionInfo[1]); //[1] nattempts
				success = Double.parseDouble(questionInfo[2]);//[2] nsuccess
			}catch(Exception e){}
			unlearnedRatio = 1 - (success+1)/(attempt+1);
			contentUnlearnedRatioMap.put(question, unlearnedRatio);
		}

		// for examples
		double distinctLines = 0.0;
		double totalLines = 0.0;
		for (Entry<String, String[]> entry : examples_activity.entrySet()) {
			String example = entry.getKey();
			String[] exampleInfo = entry.getValue();
			distinctLines = 0.0;
			totalLines = 0.0;
			try {
				distinctLines = Double.parseDouble(exampleInfo[2]); //[2] distinctactions
				totalLines = Double.parseDouble(exampleInfo[3]);//[3] totallines
			} catch (Exception e) {
			}
			unlearnedRatio = 1 - (distinctLines + 1) / (totalLines + 1);
			contentUnlearnedRatioMap.put(example, unlearnedRatio);
		}

		/* Step3: all contents can be ranked by aggregating their corresponding values in these three maps and dividing by 3:
		 * 1) contentPrerequisiteKnowledgeMap 
		 * 2)contentImpactMap 
		 * 3)contentUnlearnedRatioMap
		 * The result is the final rank of content in the contentRankMap. Rank is between 0 and 1. */
		double rank = 0.0;
		for (String content : content_concepts.keySet())
		{		
			prerequisiteKnowledgeRatio = 0.0;
			impactRatio = 0.0;
			unlearnedRatio = 0.0;
			if (contentPrerequisiteKnowledgeMap.get(content) != null)
				prerequisiteKnowledgeRatio = contentPrerequisiteKnowledgeMap.get(content);
			if(contentImpactMap.get(content) != null)
				impactRatio = contentImpactMap.get(content);
			if(contentUnlearnedRatioMap.get(content) != null)
				unlearnedRatio = contentUnlearnedRatioMap.get(content);
			rank = (prerequisiteKnowledgeRatio + impactRatio + unlearnedRatio)/3.0; // rank is between 0 and 1 for each content
			if (rank > rec_cm.rec_score_threshold)
				contentSequencing.put(content, rank); //TODO check this is the right place
		}
		return contentSequencing;
	}

	public HashMap<String, ArrayList<String[]>> getContentKCs(String domain, String[] contentList) {
        HashMap<String, ArrayList<String[]>> res = null;
        try {
            String url = contentKCURL + "?domain=" + domain;
            //System.out.println(url);
            JSONObject json = readJsonFromUrl(url);
            //System.out.println("\n\n"+json.toString()+"\n\n");
            if (json.has("error")) {
                //System.out.println("HERE ");
                System.out
                        .println("Error:[" + json.getString("errorMsg") + "]");
            } else {
                res = new HashMap<String, ArrayList<String[]>>();
                JSONArray contents = json.getJSONArray("content");

                for (int i = 0; i < contents.length(); i++) {
                    JSONObject jsonobj = contents.getJSONObject(i);
                    String content_name = jsonobj.getString("content_name");
                    // if the content exist in the course
                    if (Arrays.asList(contentList).contains(content_name)){
                        //System.out.println(content_name);
                        String conceptListStr = jsonobj.getString("concepts");
                        ArrayList<String[]> conceptList;
                        if (conceptListStr == null
                                || conceptListStr.equalsIgnoreCase("[null]")
                                || conceptListStr.length() == 0) {
                            conceptList = null;
                        } else {
                            conceptList = new ArrayList<String[]>();
                            String[] concepts = conceptListStr.split(";");
                            for (int j = 0; j < concepts.length; j++) {
                                String[] concept = concepts[j].split(",");
                                conceptList.add(concept); // concept_name, weight, direction
                                //System.out.println("  " + concept[0] + " " + concept[1] + " " + concept[2]);
                            }
                        }
                        res.put(content_name, conceptList);
                        
                    }

                    // System.out.println(jsonobj.getString("name"));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return res;
    }
	
	// CALLING A UM SERVICE
    public HashMap<String, double[]> getConceptLevels(String usr, String domain,
            String grp) {
        HashMap<String, double[]> user_concept_knowledge_levels = new HashMap<String, double[]>();
        try {
            URL url = null;
            if (domain.equalsIgnoreCase("java")) {
                url = new URL(conceptLevelsServiceURL
                        + "?typ=con&dir=out&frm=xml&app=25&dom=java_ontology"
                        + "&usr=" + URLEncoder.encode(usr, "UTF-8") + "&grp="
                        + URLEncoder.encode(grp, "UTF-8"));

            }

            if (domain.equalsIgnoreCase("sql")) {
                url = new URL(conceptLevelsServiceURL
                        + "?typ=con&dir=out&frm=xml&app=23&dom=sql_ontology"
                        + "&usr=" + URLEncoder.encode(usr, "UTF-8") + "&grp="
                        + URLEncoder.encode(grp, "UTF-8"));

            }
            if (url != null)
                user_concept_knowledge_levels = processUserKnowledgeReport(url);
            // System.out.println(url.toString());
        } catch (Exception e) {
            user_concept_knowledge_levels = null;
            e.printStackTrace();
        }
        return user_concept_knowledge_levels;

    }
    
    private static HashMap<String, double[]> processUserKnowledgeReport(URL url) {

        HashMap<String, double[]> userKnowledgeMap = new HashMap<String, double[]>();
        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory
                    .newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(url.openStream());
            doc.getDocumentElement().normalize();

            NodeList nList = doc.getElementsByTagName("concept");

            for (int temp = 0; temp < nList.getLength(); temp++) {

                Node nNode = nList.item(temp);
                if (nNode.getNodeType() == Node.ELEMENT_NODE) {

                    Element eElement = (Element) nNode;
                    NodeList cogLevels = eElement
                            .getElementsByTagName("cog_level");
                    for (int i = 0; i < cogLevels.getLength(); i++) {
                        Node cogLevelNode = cogLevels.item(i);
                        if (cogLevelNode.getNodeType() == Node.ELEMENT_NODE) {
                            Element cogLevel = (Element) cogLevelNode;
                            if (getTagValue("name", cogLevel).trim().equals(
                                    "application")) {
                                // System.out.println(getTagValue("name",
                                // eElement));
                                double[] levels = new double[1];
                                levels[0] = Double.parseDouble(getTagValue("value",cogLevel).trim());
                                userKnowledgeMap.put(
                                        getTagValue("name", eElement),
                                        levels);
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {

            e.printStackTrace();
            return null;
        }
        return userKnowledgeMap;
    }
    
    private static String getTagValue(String sTag, Element eElement) {
        NodeList nlList = eElement.getElementsByTagName(sTag).item(0)
                .getChildNodes();
        Node nValue = (Node) nlList.item(0);
        return nValue.getNodeValue();
    }

    static class ValueComparator implements Comparator<String> {

	    Map<String, Double> base;
	    public ValueComparator(Map<String, Double> base) {
	        this.base = base;
	    }

	    // Note: this comparator sorts the values descendingly, so that the best activity is in the first element.
	    public int compare(String a, String b) {
	    	if (base.get(a) >= base.get(b)) {
	            return -1;
	        } else {
	            return 1;
	        } // 
	    } // returning 0 would merge keys	   
	}
}
