package info.archinnov.achilles.entity.type;


import java.util.Iterator;

/**
 * KeyValueIterator
 * 
 * @author DuyHai DOAN
 * 
 */
public interface KeyValueIterator<K, V> extends Iterator<KeyValue<K, V>>
{

	public K nextKey();

	public V nextValue();

	public Integer nextTtl();
}
