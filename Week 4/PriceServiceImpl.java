package vn.edu.topica.eco.api.middleware.service.v1.impl;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import vn.edu.topica.eco.api.middleware.aop.Logging.MaskedParam;
import vn.edu.topica.eco.api.middleware.constant.CacheConst;
import vn.edu.topica.eco.api.middleware.model.magento.course.CoursePrice;
import vn.edu.topica.eco.api.middleware.model.magento.course.MagentoCourse;
import vn.edu.topica.eco.api.middleware.model.support.Response;
import vn.edu.topica.eco.api.middleware.repository.PriceRepository;
import vn.edu.topica.eco.api.middleware.service.v1.PriceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static vn.edu.topica.eco.api.middleware.constant.CacheConst.CacheName.PRICE_CACHE_BLOCK;

@Service
@Slf4j
public class PriceServiceImpl implements PriceService {

  private PriceRepository priceRepository;
  private CacheManager cacheManager;

  @Autowired
  public PriceServiceImpl(PriceRepository priceRepository,
                          CacheManager cacheManager) {
    this.priceRepository = priceRepository;
    this.cacheManager = cacheManager;
  }

  /* Call Magento API then use method cachePriceData() to update PRICE_CACHE_BLOCK if response has
   * status code 200 and has none null body
   * @param A list of string of course sku */
  @Override
  public void updateExpiredPrices(List<String> skus) throws Exception {
    Response magentoResponse = priceRepository.getCoursePrices(skus);
    if (magentoResponse.isOk()) {
      cachePriceData((List<CoursePrice>) magentoResponse.getData());
    }
  }

  /*When get course from cache, invoke this method to update course's price from PRICE_CACHE_BLOCK
   * @param a list courses */
  @Override
  public void updateCoursePrice(@MaskedParam(maskedSpel = "'size:' + size()") List courses)
    throws Exception {
    List<String> expiredCachedPriceCourseSkus = new ArrayList<>();
    long expiredTime = System.currentTimeMillis() - CacheConst.CACHED_COURSE_PRICE_TTL;
    for (Object course : courses) {
      PriceConverter converter = PriceConverterFactory.getConverter(course);
      if (converter == null) {
        continue;
      }
      String sku = converter.getSku();
      CoursePrice coursePrice = getCoursePriceBySku(sku);
      if (coursePrice == null) {
        continue;
      }
      if (expiredTime < coursePrice.getCachedTime()) {
        converter.updatePrice(coursePrice);
      } else {
        expiredCachedPriceCourseSkus.add(converter.getSku());
      }
    }
    if (!CollectionUtils.isEmpty(expiredCachedPriceCourseSkus)) {
      updateExpiredPrices(expiredCachedPriceCourseSkus);
    }
  }

  /*Get a CoursePrice by course sku from cache map
   * @param String course sku
   * @return CoursePrice with key == input sku */
  private CoursePrice getCoursePriceBySku(String sku) {
    if (Strings.isNullOrEmpty(sku)) return null;
    Map<String, CoursePrice> cachedPriceMap = getCachedPriceMap();
    if (cachedPriceMap == null) return null;
    return cachedPriceMap.get(sku);
  }

  /*Get value of PRICE_CACHE_BLOCK with key is PRICE_MAP_KEY, as a Map<String, CoursePrice>
  * @return a Map or null if any exception been thrown out */
  private Map<String, CoursePrice> getCachedPriceMap() {
    Cache cache = cacheManager.getCache(PRICE_CACHE_BLOCK.name());
    try {
      return cache.get(PRICE_MAP_KEY, Map.class);
    } catch (Exception e){
      log.error("PRICE_CACHE_BLOCK get cache failed!!! {}", ExceptionUtils.getRootCauseMessage(e));
      return null;
    }
  }

  /*Transform a List<MagentoCourse> to List<CoursePrice>, prepair for put to cache
  * @param a list of MagentoCourse */
  @Override
  public void cacheCoursePrice(@MaskedParam(maskedSpel = "'size:' + size()") List courses) {
    List<CoursePrice> coursePrices = new ArrayList<>();
    CoursePrice coursePrice;
    for (Object course : courses) {
      PriceConverter converter = PriceConverterFactory.getConverter(course);
      if (converter != null) {
        coursePrice = converter.toCoursePrice();
        if (coursePrice != null && !Strings.isNullOrEmpty(coursePrice.getSku())) {
          coursePrices.add(coursePrice);
        }
      }
    }
    cachePriceData(coursePrices);
  }

  /*PRICE_CACHE_BLOCK key*/
  private static final String PRICE_MAP_KEY = "all_price";

  /*Create a synchronized Map for putting to PRICE_CACHE_BLOCK with key is PRICE_MAP_KEY
  * @return new Concurrent Map */
  private synchronized Map<String, CoursePrice> createPricesMap() {
    synchronized (PRICE_MAP_KEY) {
      return Maps.newConcurrentMap();
    }
  }

  /*Create a Map<String,CoursePrice> from List<CoursePrice> with key is course sku
  * then put to PRICE_CACHE_BLOCK cache with key PRICE_MAP_KEY
  * @param list of CoursePrice */
  private void cachePriceData(List<CoursePrice> coursePrices) {
    if (CollectionUtils.isEmpty(coursePrices)) {
      return;
    }

    Map<String, CoursePrice> cachedMap = getCachedPriceMap();
    if (cachedMap == null) {
      cachedMap = createPricesMap();
    }

    long cachedTime = System.currentTimeMillis();
    for (CoursePrice price : coursePrices) {
      price.setCachedTime(cachedTime);
      cachedMap.put(price.getSku(), price);
    }

    Cache cache = cacheManager.getCache(PRICE_CACHE_BLOCK.name());
    if (cache != null) {
      cache.put(PRICE_MAP_KEY, cachedMap);
    }
  }

  public interface PriceConverter {
    /*Get course sku of MagentoCourse
    * @return a String*/
    String getSku();

    /*Update price for a course
    * @param a CoursePrice*/
    void updatePrice(CoursePrice coursePrice);

    /*Convert a MagentoCourse to CoursePrice
    * @return a CoursePrice*/
    CoursePrice toCoursePrice();
  }

  public static class PriceConverterFactory {

    /*Create a converter to validate then convert an Object to MagentoCoursePriceConverter
    * @param an Object we expect that it's an instance of MagentoCourse
    * @return new MagentoCoursePriceConverter
    * or null if input Object is not an instance of MagentoCourse */
    public static PriceConverter getConverter(Object course) {
      if (course instanceof MagentoCourse) {
        return new MagentoCoursePriceConverter((MagentoCourse) course);
      }
      return null;
    }
  }

  public static class MagentoCoursePriceConverter implements PriceConverter {

    MagentoCourse course;

    public MagentoCoursePriceConverter(MagentoCourse course) {
      this.course = course;
    }

    @Override
    public String getSku() {
      return course.getSku();
    }

    @Override
    public void updatePrice(CoursePrice coursePrice) {
      course.setFinalPrice(coursePrice.getDiscountPrice());
      course.setPrice(coursePrice.getOriginalPrice());
    }

    @Override
    public CoursePrice toCoursePrice() {
      CoursePrice coursePrice = new CoursePrice();
      coursePrice.setOriginalPrice(course.getPrice());
      coursePrice.setDiscountPrice(course.getFinalPrice());
      coursePrice.setSku(course.getSku());
      coursePrice.setCachedTime(System.currentTimeMillis());
      return coursePrice;
    }
  }
}
