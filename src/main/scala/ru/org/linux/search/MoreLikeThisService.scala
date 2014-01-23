package ru.org.linux.search

import org.springframework.stereotype.Service
import org.springframework.beans.factory.annotation.Autowired
import org.elasticsearch.client.Client
import ru.org.linux.topic.{TopicDao, Topic}
import scala.collection.JavaConversions._
import org.elasticsearch.index.query.QueryBuilders._
import com.typesafe.scalalogging.slf4j.Logging
import org.elasticsearch.index.query.FilterBuilders._
import ru.org.linux.tag.TagRef
import org.joda.time.DateTime
import scala.beans.BeanProperty
import ru.org.linux.util.StringUtil

@Service
class MoreLikeThisService @Autowired() (
  client:Client,
  topicDao:TopicDao
) extends Logging {
  // TODO async - return ListenableFuture
  // TODO timeout
  // TODO errors
  def search(topic:Topic, tags:java.util.List[TagRef]):java.util.List[MoreLikeThisTopic] = {
    // TODO boost tags
    // see http://stackoverflow.com/questions/15300650/elasticsearch-more-like-this-api-vs-more-like-this-query

    // TODO message body

    val mltQuery = boolQuery()

    mltQuery.should(titleQuery(topic))

    if (!tags.isEmpty) {
      mltQuery.should(tagsQuery(tags.map(_.name).toSeq))
    }

    val rootFilter = boolFilter()
    rootFilter.must(termFilter("is_comment", "false"))
    rootFilter.mustNot(idsFilter(SearchQueueListener.MESSAGES_TYPE).addIds(topic.getId.toString))

    val rootQuery = filteredQuery(mltQuery, rootFilter)

    val query = client
      .prepareSearch(SearchQueueListener.MESSAGES_INDEX)
      .setTypes(SearchQueueListener.MESSAGES_TYPE)
      .setQuery(rootQuery)

    val result = query.execute().actionGet()

    // TODO filter out ids with MessageNotFoundException
    result.getHits.map(hit => {
      val topic = topicDao.getById(hit.getId.toInt)
      val postdate = new DateTime(topic.getPostdate)

      MoreLikeThisTopic(
        title=StringUtil.processTitle(topic.getTitleUnescaped),
        link=topic.getLink,
        year=postdate.year().get()
      )
    }).toSeq
  }

  private def titleQuery(topic:Topic) = moreLikeThisFieldQuery("title")
    .likeText(topic.getTitleUnescaped)
    .minTermFreq(0)
    .minDocFreq(0)

  private def tagsQuery(tags:Seq[String]) = {
    val root = boolQuery()

    tags foreach { tag =>
      root.should(termQuery("tag", tag))
    }

    root
  }
}

case class MoreLikeThisTopic(
  @BeanProperty title:String,
  @BeanProperty link:String,
  @BeanProperty year:Int
)
