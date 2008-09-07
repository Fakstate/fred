package freenet.support;

import java.util.Enumeration;
import java.util.NoSuchElementException;

import junit.framework.TestCase;
import freenet.support.DoublyLinkedListImpl.Item;

public class DoublyLinkedListImplTest extends TestCase {
	private static class T extends Item<T> {
		int value;
		boolean isClone;

		T(int v) {
			value = v;
		}

		public T clone() {
			T c = new T(value);
			c.isClone = true;
			return c;
		}

		public String toString() {
			if (isClone)
				return "[" + value + "]";
			else
				return "(" + value + ")";
		}

		void assertV(int v) {
			assertEquals(v, value);
		}

		public void assertIsClone() {
			assertTrue("isClone", isClone);
		}

		public void assertIsNotClone() {
			assertFalse("isClone", isClone);
		}
	}

	public void testForwardPushPop() {
		DoublyLinkedList<T> list = new DoublyLinkedListImpl<T>();
		list.push(new T(0));
		list.push(new T(1));
		list.push(new T(2));
		list.push(new T(3));

		assertFalse("isEmpty()", list.isEmpty());
		((T) list.pop()).assertV(3);
		assertFalse("isEmpty()", list.isEmpty());
		((T) list.pop()).assertV(2);
		assertFalse("isEmpty()", list.isEmpty());

		// add again
		list.push(new T(4));
		list.push(new T(5));

		((T) list.pop()).assertV(5);
		assertFalse("isEmpty()", list.isEmpty());
		((T) list.pop()).assertV(4);
		assertFalse("isEmpty()", list.isEmpty());
		((T) list.pop()).assertV(1);
		assertFalse("isEmpty()", list.isEmpty());
		((T) list.pop()).assertV(0);

		assertTrue("isEmpty()", list.isEmpty());
		assertNull("pop()", list.pop());
	}

	public void testForwardShiftUnshift() {
		DoublyLinkedList<T> list = new DoublyLinkedListImpl<T>();
		list.unshift(new T(0));
		list.unshift(new T(1));
		list.unshift(new T(2));
		list.unshift(new T(3));

		assertFalse("isEmpty()", list.isEmpty());
		((T) list.shift()).assertV(3);
		assertFalse("isEmpty()", list.isEmpty());
		((T) list.shift()).assertV(2);
		assertFalse("isEmpty()", list.isEmpty());

		// add again
		list.unshift(new T(4));
		list.unshift(new T(5));

		((T) list.shift()).assertV(5);
		assertFalse("isEmpty()", list.isEmpty());
		((T) list.shift()).assertV(4);
		assertFalse("isEmpty()", list.isEmpty());
		((T) list.shift()).assertV(1);
		assertFalse("isEmpty()", list.isEmpty());
		((T) list.shift()).assertV(0);

		assertTrue("isEmpty()", list.isEmpty());
		assertNull("shift()", list.shift());
	}

	public void testClearSize() {
		DoublyLinkedList<T> list = new DoublyLinkedListImpl<T>();
		list.unshift(new T(0));
		list.unshift(new T(1));
		list.unshift(new T(2));
		list.unshift(new T(3));

		assertEquals("size()", 4, list.size());
		assertFalse("isEmpty()", list.isEmpty());
		((T) list.shift()).assertV(3);
		assertEquals("size()", 3, list.size());
		assertFalse("isEmpty()", list.isEmpty());
		((T) list.shift()).assertV(2);
		assertEquals("size()", 2, list.size());
		assertFalse("isEmpty()", list.isEmpty());

		list.clear();

		assertEquals("size()", 0, list.size());
		assertTrue("isEmpty()", list.isEmpty());

		// add again
		list.unshift(new T(4));
		list.unshift(new T(5));
		assertEquals("size()", 2, list.size());
		assertFalse("isEmpty()", list.isEmpty());

		((T) list.shift()).assertV(5);
		((T) list.shift()).assertV(4);

		assertEquals("size()", 0, list.size());
		assertTrue("isEmpty()", list.isEmpty());
	}

	public void testClone() {
		DoublyLinkedList<T> list = new DoublyLinkedListImpl<T>();
		for (int i = 0; i < 3; i++) {
			list.unshift(new T(i));
		}

		DoublyLinkedList<T> listClone = list.clone();

		for (int i = 2; i >= 0; i--) {
			T t = (T) list.shift();
			t.assertV(i);
			t.assertIsNotClone();

			T tc = (T) listClone.shift();
			tc.assertV(i);
			tc.assertIsClone();
		}
	}

	public void testShiftN() {
		DoublyLinkedList<T> list = new DoublyLinkedListImpl<T>();

		for (int i = 0; i < 5; i++) {
			list.push(new T(i));
		}

		DoublyLinkedList<T> list2 = list.shift(2);
		assertEquals("list2.size()", 2, list2.size());
		((T) list2.shift()).assertV(0);
		((T) list2.shift()).assertV(1);
		assertTrue("list2.isEmpty()", list2.isEmpty());

		assertEquals("list.size()", 3, list.size());
		((T) list.shift()).assertV(2);

		list2 = list.shift(20);
		assertTrue("list.isEmpty()", list.isEmpty());
		((T) list2.shift()).assertV(3);
		((T) list2.shift()).assertV(4);
		assertTrue("list2.isEmpty()", list2.isEmpty());

		list2 = list.shift(20);
		assertTrue("list2.isEmpty()", list2.isEmpty());
	}

	public void testPopN() {
		DoublyLinkedList<T> list = new DoublyLinkedListImpl<T>();

		for (int i = 0; i < 5; i++) {
			list.unshift(new T(i));
		}

		DoublyLinkedList<T> list2 = list.pop(2);
		assertEquals("list2.size()", 2, list2.size());
		((T) list2.pop()).assertV(0);
		((T) list2.pop()).assertV(1);
		assertTrue("list2.isEmpty()", list2.isEmpty());

		assertEquals("list.size()", 3, list.size());
		((T) list.pop()).assertV(2);

		list2 = list.pop(20);
		assertTrue("list.isEmpty()", list.isEmpty());
		((T) list2.pop()).assertV(3);
		((T) list2.pop()).assertV(4);
		assertTrue("list2.isEmpty()", list2.isEmpty());

		list2 = list.pop(20);
		assertTrue("list2.isEmpty()", list2.isEmpty());
	}

	public void testHeadTail() {
		DoublyLinkedList<T> list = new DoublyLinkedListImpl<T>();

		assertNull("head() == null", list.head());
		assertNull("tail() == null", list.tail());

		T[] array = new T[5];
		for (int i = 0; i < 5; i++) {
			array[i] = new T(i);
			list.push(array[i]);
		}

		assertTrue("head() == 0", array[0] == list.head());
		assertTrue("tail() == 4", array[4] == list.tail());

		list.shift();
		assertTrue("head() == 1", array[1] == list.head());
		assertTrue("tail() == 4", array[4] == list.tail());

		list.pop();
		assertTrue("head() == 1", array[1] == list.head());
		assertTrue("tail() == 3", array[3] == list.tail());

		list.clear();

		assertNull("head() == null", list.head());
		assertNull("tail() == null", list.tail());
	}

	public void testIternator() {
		DoublyLinkedList<T> list = new DoublyLinkedListImpl<T>();
		T[] array = new T[5];

		for (int i = 0; i < 5; i++) {
			array[i] = new T(i);
			list.push(array[i]);
		}

		// manual, forward
		T h = (T) list.head();
		for (int i = 0; i < 5; i++) {
			assertEquals("manual iternate, forward", array[i], h);
			//assertEquals("DoublyLinkedList.next() == Item.next()", h.getNext(), list.next(h));
			assertEquals("hasNext()", i != 4, list.hasNext(h));
			assertEquals("hasPrev()", i != 0, list.hasPrev(h));

			h.assertV(i);

			h = (T) list.next(h);
		}
		assertEquals("h==null", null, h);

		// manual, reverse
		T t = (T) list.tail();
		for (int i = 4; i >= 0; i--) {
			assertEquals("manual iternate, reverse", array[i], t);
			//assertEquals("DoublyLinkedList.prev() == Item.getPrev()", tail.getPrev(), list.prev(tail));
			assertEquals("hasNext()", i != 4, list.hasNext(t));
			assertEquals("hasPrev()", i != 0, list.hasPrev(t));

			t.assertV(i);

			t = (T) list.prev(t);
		}
		assertNull("t==null", t);

		Enumeration<T> e = list.elements();
		for (int i = 0; i < 5; i++) {
			assertTrue("hasMoreElements()", e.hasMoreElements());

			T n = e.nextElement();
			n.assertV(i);

			assertEquals("hasMoreElements()", i != 4, e.hasMoreElements());
		}
		try {
			e.nextElement();
			fail("NoSuchElementException");
		} catch (NoSuchElementException nsee) {
		}
	}

	public void testRandomRemovePush() {
		DoublyLinkedList<T> list = new DoublyLinkedListImpl<T>();
		T[] array = new T[5];

		for (int i = 0; i < 5; i++) {
			array[i] = new T(i);
			list.push(array[i]);
		}

		assertEquals(list.remove(array[3]), array[3]);
		list.push(array[3]);

		assertNull(list.remove(new T(-1)));

		((T) list.shift()).assertV(0);
		((T) list.shift()).assertV(1);
		((T) list.shift()).assertV(2);
		((T) list.shift()).assertV(4);
		((T) list.shift()).assertV(3);
	}

	public void testRandomShiftPush() {
		DoublyLinkedList<T> list = new DoublyLinkedListImpl<T>();
		list.push(new T(0));
		list.push(new T(1));
		list.unshift(new T(2));
		list.push(new T(3));
		list.unshift(new T(4));
		list.unshift(new T(5));

		((T) list.shift()).assertV(5);
		((T) list.pop()).assertV(3);
		((T) list.pop()).assertV(1);
		((T) list.pop()).assertV(0);
		((T) list.shift()).assertV(4);
		((T) list.shift()).assertV(2);
	}
}
