import java.util.List;
import java.util.Properties;
//import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreAnnotations.NamedEntityTagAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TextAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
//import edu.stanford.nlp.neural.rnn.RNNCoreAnnotations; //the ".neural" was missing
//import edu.stanford.nlp.sentiment.SentimentCoreAnnotations;
//import edu.stanford.nlp.sentiment.SentimentCoreAnnotations.SentimentAnnotatedTree;
//import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.util.CoreMap;

import java.util.HashMap;

public class namedEntityRecognitionHandler {

    // Extract only the following entity types: PERSON, LOCATION, ORGANIZATION.

    private Properties props;
    private static StanfordCoreNLP NERPipeline;

    public namedEntityRecognitionHandler() {
        props = new Properties();
        props.put("annotators", "tokenize , ssplit, pos, lemma, ner");
        NERPipeline = new StanfordCoreNLP(props);
    }

    // The following code extracts named entities from a review:
    public HashMap<String,String> printEntities(String review) {
        // create an empty Annotation just with the given text
        Annotation document = new Annotation(review);
        // run all Annotators on this text
        NERPipeline.annotate(document);
        // these are all the sentences in this document
        // a CoreMap is essentially a Map that uses class objects as keys and has values with custom types;
        List<CoreMap> sentences = document.get(SentencesAnnotation.class);
        HashMap<String, String> entities = new HashMap<String, String>();
        for (CoreMap sentence : sentences) {
            // traversing the words in the current sentence
            // a CoreLabel is a CoreMap with additional token-specific methods
            for (CoreLabel token : sentence.get(TokensAnnotation.class)) {
                /*// this is the text of the token
                String word = token.get(TextAnnotation.class);
                // this is the NER label of the token
                String ne = token.get(NamedEntityTagAnnotation.class);
                System.out.println("\t-" + word + ":" + ne);*/
                String ne = token.get(NamedEntityTagAnnotation.class);
                if (ne.equals("PERSON") || ne.equals("LOCATION") || ne.equals("ORGANIZATION")) {
                    String word = token.get(TextAnnotation.class);
                    entities.put(word, ne);
                }
            }
        }
        return entities;
    }

    public String getEntitiesInStrFormat(String review) {
        String persons = "";
        String locations = "";
        String organizations = "";
        Annotation document = new Annotation(review); // create an empty Annotation just with the given text
        NERPipeline.annotate(document); // run all Annotators on this text
        // these are all the sentences in this document, a CoreMap is essentially a Map that uses class objects as keys and has values with custom types
        List<CoreMap> sentences = document.get(SentencesAnnotation.class);
        for (CoreMap sentence : sentences) {
            for (CoreLabel token : sentence.get(TokensAnnotation.class)) {
                String ne = token.get(NamedEntityTagAnnotation.class);
                String word = token.get(TextAnnotation.class); 
                if (ne.equals("PERSON")) {
                    persons += word + ", ";
                } else if (ne.equals("LOCATION")) {
                    locations += word + ", ";
                } else if (ne.equals("ORGANIZATION")) {
                    organizations += word + ", ";
                }
            }
        }
        String result = "";
        if(persons.length() > 0) { 
            persons = persons.substring(0, persons.length() - 2); // Remove the last comma and space
            result += "[ PERSON: " + persons + " ] ";
        }
        if(locations.length() > 0) {
            locations = locations.substring(0, locations.length() - 2); // Remove the last comma and space
            result += "[ LOCATION: " + locations + " ] ";
        }
        if(organizations.length() > 0) { 
            organizations = organizations.substring(0, organizations.length() - 2); // Remove the last comma and space
            result += "[ ORGANIZATION: " + organizations + " ] ";
        }
        return result;
    }

}
