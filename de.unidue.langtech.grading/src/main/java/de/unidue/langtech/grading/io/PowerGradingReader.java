package de.unidue.langtech.grading.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.apache.commons.io.FileUtils;
import org.apache.uima.UimaContext;
import org.apache.uima.collection.CollectionException;
import org.apache.uima.fit.component.JCasCollectionReader_ImplBase;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.util.Progress;
import org.apache.uima.util.ProgressImpl;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import de.tudarmstadt.ukp.dkpro.core.api.metadata.type.DocumentMetaData;
import de.tudarmstadt.ukp.dkpro.core.api.resources.ResourceUtils;
import de.tudarmstadt.ukp.dkpro.tc.api.type.TextClassificationOutcome;

public class PowerGradingReader
    extends JCasCollectionReader_ImplBase
{
    
//	public static final Integer[] questionIds = new Integer[] { 1, 2, 3, 4, 5, 6, 7, 8, 13, 20 };
	// excluding 13
	public static final Integer[] questionIds = new Integer[] { 1, 2, 3, 4, 5, 6, 7, 8, 20 };

    protected static final String DEFAULT_LANGUAGE = "en";
    
    public static final String PARAM_INPUT_FILE = "InputFile";
    @ConfigurationParameter(name = PARAM_INPUT_FILE, mandatory = true)
    protected String inputFileString;
    protected URL inputFileURL;
    
    public static final String PARAM_LANGUAGE = "Language";
    @ConfigurationParameter(name = PARAM_LANGUAGE, mandatory = false, defaultValue = DEFAULT_LANGUAGE)
    protected String language;
    
    public static final String PARAM_ENCODING = "Encoding";
    @ConfigurationParameter(name = PARAM_ENCODING, mandatory = false, defaultValue = "UTF-8")
    private String encoding;
    
    public static final String PARAM_SEPARATOR = "Separator";
    @ConfigurationParameter(name = PARAM_SEPARATOR, mandatory = false, defaultValue = "\t")
    private String separator;
    
    public static final String PARAM_QUESTION_ID = "QuestionId";
    @ConfigurationParameter(name = PARAM_QUESTION_ID, mandatory = false, defaultValue = "-1")
    protected Integer requestedQuestionId; 
    
    protected int currentIndex;    

    protected Queue<PowerGradingItem> items;
    
    private List<Integer> grades1;
    private List<Integer> grades2;
    private List<Integer> grades3;

    
    @Override
    public void initialize(UimaContext aContext)
        throws ResourceInitializationException
    {
        items = new LinkedList<PowerGradingItem>();

        if (requestedQuestionId != null && requestedQuestionId < 0) {
            getLogger().warn("Invalid questionId - using all documents");
            requestedQuestionId = null;
        }
        
        grades1 = new ArrayList<Integer>();
        grades2 = new ArrayList<Integer>();
        grades3 = new ArrayList<Integer>();
        
        try {
            inputFileURL = ResourceUtils.resolveLocation(inputFileString, this, aContext);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            inputFileURL.openStream(),
                            encoding
                    )
            );
            String nextLine;
            while ((nextLine = reader.readLine()) != null) {
                // skip the header
                if (nextLine.startsWith("student\t")) {
                    nextLine = reader.readLine();
                }
                
                String[] nextItem = nextLine.split(separator);
                
                String studentId = null;
                int questionId   = -1;
                String text      = null;
                int grader1      = -1;
                int grader2      = -1;
                int grader3      = -1;
    
                if (nextItem.length == 6) {
                    studentId  = nextItem[0];
                    questionId = Integer.parseInt(nextItem[1]);
                    text       = nextItem[2];
                    grader1    = Integer.parseInt(nextItem[3]);
                    grader2    = Integer.parseInt(nextItem[4]);
                    grader3    = Integer.parseInt(nextItem[5]);
                }
                else {
                    throw new IOException("Wrong file format.");
                }
                
                // HOTFIX for Issue 445 in DKPro Core
                text = text.replace("’", "'");
                
                // if validEssaySetId is set, then skip if not equal with current 
                if (requestedQuestionId != null && requestedQuestionId != questionId) {
                    continue;
                }
               
                PowerGradingItem newItem = new PowerGradingItem(studentId, questionId, text, grader1, grader2, grader3);

                items.add(newItem);
                
                if (newItem.getGrader1() != -1) {
                    grades1.add(newItem.getGrader1());
                    grades2.add(newItem.getGrader2());
                    grades3.add(newItem.getGrader3());           	
                }
            }   
        }
        catch (Exception e) {
            throw new ResourceInitializationException(e);
        }
        
        Gson gson = new Gson();
        Type listType = new TypeToken<List<Integer>>() {}.getType();

        try {
			FileUtils.writeStringToFile(new File("target/grades1.txt"), gson.toJson(grades1, listType));
			FileUtils.writeStringToFile(new File("target/grades2.txt"), gson.toJson(grades2, listType));
			FileUtils.writeStringToFile(new File("target/grades3.txt"), gson.toJson(grades3, listType));
		} catch (IOException e) {
			throw new ResourceInitializationException(e);
		}

        currentIndex = 0;
    }
    
    @Override
    public boolean hasNext()
        throws IOException 
    {
        return !items.isEmpty();
    }

    @Override
    public void getNext(JCas jcas)
    	throws IOException, CollectionException
    {
        PowerGradingItem item = items.poll();
        getLogger().debug(item);
                
        try
        {
	        if (language != null) {
	            jcas.setDocumentLanguage(language);
	        }
	        else {
	            jcas.setDocumentLanguage(DEFAULT_LANGUAGE);
	        }
	        
	        jcas.setDocumentText(item.getText());
	        	        	
            DocumentMetaData dmd = DocumentMetaData.create(jcas);
            dmd.setDocumentId(String.valueOf(item.getStudentId())); 
            dmd.setDocumentTitle(item.getStudentId());
            dmd.setDocumentUri(inputFileURL.toURI().toString());
            dmd.setCollectionId(Integer.toString(item.getQuestionId()));
	        
        } 
		catch (URISyntaxException e) {
			throw new CollectionException(e);
		}
        
        TextClassificationOutcome outcome = new TextClassificationOutcome(jcas);
        outcome.setOutcome(Integer.toString(item.getGrader1()));
        outcome.addToIndexes();

        currentIndex++;
    }
    
    
    
    @Override
    public Progress[] getProgress()
    {
        return new Progress[] { new ProgressImpl(currentIndex, currentIndex, Progress.ENTITIES) };
    }
}
